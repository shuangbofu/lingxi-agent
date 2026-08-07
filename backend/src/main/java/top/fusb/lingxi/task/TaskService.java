package top.fusb.lingxi.task;

import top.fusb.lingxi.dto.AgentTaskDefinition;
import top.fusb.lingxi.dto.PageResult;
import top.fusb.lingxi.dto.TaskContinueRequest;
import top.fusb.lingxi.dto.TaskCreateRequest;
import top.fusb.lingxi.dto.TaskAttachmentResponse;
import top.fusb.lingxi.dto.TaskInputValue;
import top.fusb.lingxi.dto.TaskResponse;
import top.fusb.lingxi.dto.TaskRerunRequest;
import top.fusb.lingxi.dto.TaskResumeRequest;
import top.fusb.lingxi.dto.TaskRoundSummaryResponse;
import top.fusb.lingxi.dto.UserResponse;
import top.fusb.lingxi.entity.AnalysisPremiseEntity;
import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.entity.TaskRuntimeContextValueEntity;
import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.enums.TaskStatus;
import top.fusb.lingxi.enums.UserRole;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.kit.DefinitionAssetUrlKit;
import top.fusb.lingxi.kit.TextKit;
import top.fusb.lingxi.prompt.PromptService;
import top.fusb.lingxi.prompt.TaskPrompt;
import top.fusb.lingxi.repository.AgentTaskRepository;
import top.fusb.lingxi.repository.TaskRuntimeContextValueRepository;
import top.fusb.lingxi.runtime.config.RuntimeModeService;
import top.fusb.lingxi.runtime.config.RuntimeModelProfileConfig;
import top.fusb.lingxi.runtime.config.ModelCatalogService;
import top.fusb.lingxi.service.AgentCapabilityService;
import top.fusb.lingxi.service.AgentScenarioService;
import top.fusb.lingxi.service.AnalysisPremiseService;
import top.fusb.lingxi.auth.UserUsageQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.criteria.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private static final int MAX_TASK_TITLE_LENGTH = 500;
    private static final int MAX_TOTAL_INPUT_LENGTH = 100_000;
    private static final int LONG_INPUT_THRESHOLD = 500;
    private static final int LONG_INPUT_EXCERPT_LENGTH = 120;
    private static final long CANCELED_TASK_STOP_WAIT_MILLIS = 15_000L;
    private final AgentTaskRepository agentTaskRepository;
    private final PromptService promptService;
    private final TaskRunner taskRunner;
    private final AgentScenarioService agentScenarioService;
    private final AnalysisPremiseService analysisPremiseService;
    private final TaskEventService taskEventService;
    private final TaskMetricsService taskMetricsService;
    private final TaskEventStreamService taskEventStreamService;
    private final TaskContentService taskContentService;
    private final TaskInteractionService taskInteractionService;
    private final TaskRuntimeContextValueRepository taskRuntimeContextValueRepository;
    private final TransactionTemplate transactionTemplate;
    private final UserUsageQuotaService userUsageQuotaService;
    private final RuntimeModeService runtimeModeService;
    private final ModelCatalogService modelCatalogService;
    private final TaskAttachmentService taskAttachmentService;
    private final AgentCapabilityService agentCapabilityService;

    /**
     * 查询任务提问人筛选项。
     *
     * @return 有任务记录的提问人列表
     */
    @Transactional(readOnly = true)
    public List<UserResponse> owners() {
        return agentTaskRepository.findDistinctOwners().stream().map(this::toOwnerResponse).toList();
    }

    /**
     * 创建并异步执行任务。
     *
     * @param request 任务创建参数
     * @return 任务信息
     * @throws BizException 项目不存在或场景不支持时抛出
     */
    @Transactional
    public TaskResponse create(TaskCreateRequest request, UserEntity owner) {
        String runtimeCode = runtimeModeService.requireAvailable(request.getRuntimeCode(), owner);
        RuntimeModelProfileConfig modelProfile = runtimeModeService.requireAvailableModel(
                runtimeCode, request.getModelProfileId(), owner);
        agentScenarioService.requireEntity(request.getScenarioCode());
        AgentTaskDefinition definition = agentScenarioService.requireTaskDefinition(request.getScenarioCode());
        AnalysisPremiseEntity premise = analysisPremiseService.resolveForTask(request, owner);
        validatePremiseScenarioAccess(premise, definition.getCode());
        analysisPremiseService.applyPremise(request, premise, definition.getCode());
        definition.activateCapabilities(request.getInputValues());
        retainAvailableCapabilities(definition);
        validateInputSize(request);
        String scenario = definition.getScenario();
        if (definition.isUniqueBySource()
                && request.getSourceTaskId() != null
                && sourceTaskExists(request.getSourceTaskId(), definition.getCode())) {
            log.info("同源任务已存在，跳过重复创建 sourceTaskId={} scenario={}", request.getSourceTaskId(), scenario);
            return detailBySourceTask(request.getSourceTaskId(), definition.getCode(), owner);
        }
        userUsageQuotaService.requireAvailable(owner);
        List<TaskAttachmentResponse> attachments = taskAttachmentService.resolve(request.getAttachmentIds(), owner);
        String originalInput = normalizeUserInput(request, definition);
        PreparedUserInput preparedInput = prepareUserInput(originalInput, request, attachments, owner);
        String taskInput = preparedInput.userInput();
        attachments = preparedInput.attachments();
        request.setUserInput(taskInput);
        String premisePromptText = analysisPremiseService.premisePromptText(premise, definition.getCode());
        TaskPrompt prompt = promptService.buildTaskPrompt(scenario, definition, request, premisePromptText);
        AgentTaskEntity task = new AgentTaskEntity();
        applyScenarioSnapshot(task, definition);
        task.setPremise(premise);
        applyPremiseSnapshot(task, premise, definition.getCode(), premisePromptText);
        task.setOwner(owner);
        task.setScenario(scenario);
        task.setRuntimeCode(runtimeCode);
        applyModelSnapshot(task, modelProfile);
        task.setStatus(TaskStatus.PENDING);
        task.setTitle(buildTitle(scenario, definition, taskInput, attachments));
        task.setUserInput(taskInput);
        task.setInputValues(request.getInputValues());
        task.setAttachments(attachments);
        applyCapabilitySnapshot(task, definition);
        task.setRuntimeInstructions(prompt.instructions());
        task.setRuntimeUserMessage(prompt.userMessage());
        task.setPresentations(new LinkedHashSet<>(definition.getPresentations()));
        task.setFinalResponseInstructions(prompt.finalResponseInstructions());
        task.setPrompt(prompt.combined());
        task.setStdoutText("");
        task.setStderrText("");
        task.setResultText("");
        task.setSourceTaskId(request.getSourceTaskId());
        task.setConversationRootTaskId(null);
        task.setRoundNo(1);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(task.getCreatedAt());
        AgentTaskEntity saved = agentTaskRepository.save(task);
        if (saved.getConversationRootTaskId() == null) {
            saved.setConversationRootTaskId(saved.getId());
            saved = agentTaskRepository.save(saved);
        }
        log.info("create task taskId={} scenario={} runtimeCode={} model={} owner={} premiseId={}", saved.getId(), scenario,
                runtimeCode, saved.getModelIdentifier(), owner == null ? null : owner.getUsername(),
                premise == null ? null : premise.getId());
        runAfterCommit(saved.getId());
        return toResponse(saved, owner);
    }

    /**
     * 基于已有任务继续追问，创建同一会话中的下一轮任务。
     *
     * @param id 当前任务或会话内任意轮次 ID
     * @param request 继续追问内容
     * @param owner 当前用户
     * @return 新一轮任务
     * @throws BizException 任务不存在、无权限或当前会话仍在运行时抛出
     */
    @Transactional
    public TaskResponse continueRound(Long id, TaskContinueRequest request, UserEntity owner) {
        userUsageQuotaService.requireAvailable(owner);
        AgentTaskEntity source = requireAccessibleEntity(id, owner);
        Long rootId = source.getConversationRootTaskId() == null ? source.getId() : source.getConversationRootTaskId();
        List<AgentTaskEntity> rounds = conversationRounds(rootId);
        if (rounds.stream().anyMatch(round -> isActiveTask(round.getStatus()))) {
            throw new BizException(ErrorCode.TASK_ERROR, ErrorSubCode.TASK_NOT_RUNNING, "当前会话仍有任务在执行");
        }
        AgentTaskEntity latest = rounds.isEmpty() ? source : rounds.get(rounds.size() - 1);
        String userInput = TextKit.blankToNull(request == null ? null : request.getUserInput());
        List<String> attachmentIds = request == null || request.getAttachmentIds() == null ? List.of() : request.getAttachmentIds();
        if (userInput == null && attachmentIds.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "请输入继续追问内容或上传附件");
        }
        String normalizedInput = userInput == null ? "" : userInput.trim();
        TaskCreateRequest createRequest = new TaskCreateRequest();
        if (TextKit.blankToNull(latest.getScenarioCode()) == null) {
            throw new BizException(ErrorCode.TASK_ERROR, ErrorSubCode.VALIDATION_FAILED, "任务缺少场景定义，不能继续执行");
        }
        createRequest.setScenarioCode(latest.getScenarioCode());
        createRequest.setPremiseId(latest.getPremise() == null ? null : latest.getPremise().getId());
        createRequest.setSourceTaskId(latest.getId());
        createRequest.setUserInput(normalizedInput);
        createRequest.setAttachmentIds(attachmentIds);
        // 续问继承场景参数，但变更需求必须使用本轮输入，避免新旧目标同时进入 Prompt。
        createRequest.setInputValues(latest.getInputValues() == null ? List.of() : latest.getInputValues().stream().map(inputValue -> {
            TaskInputValue copiedValue = new TaskInputValue();
            copiedValue.setKey(inputValue.getKey());
            copiedValue.setValue("userInput".equals(inputValue.getKey()) ? normalizedInput : inputValue.getValue());
            return copiedValue;
        }).toList());
        agentScenarioService.requireEntity(createRequest.getScenarioCode());
        AgentTaskDefinition definition = agentScenarioService.requireTaskDefinition(createRequest.getScenarioCode());
        AnalysisPremiseEntity premise = analysisPremiseService.resolveForTask(createRequest, owner);
        validatePremiseScenarioAccess(premise, definition.getCode());
        analysisPremiseService.applyPremise(createRequest, premise, definition.getCode());
        definition.activateCapabilities(createRequest.getInputValues());
        retainAvailableCapabilities(definition);
        validateInputSize(createRequest);
        List<TaskAttachmentResponse> attachments = taskAttachmentService.resolve(createRequest.getAttachmentIds(), owner);
        PreparedUserInput preparedInput = prepareUserInput(normalizedInput, createRequest, attachments, owner);
        String taskInput = preparedInput.userInput();
        attachments = preparedInput.attachments();
        createRequest.setUserInput(taskInput);
        String premisePromptText = analysisPremiseService.premisePromptText(premise, definition.getCode());
        TaskPrompt prompt = promptService.buildTaskPrompt(
                definition.getScenario(), definition, createRequest, premisePromptText);
        AgentTaskEntity task = new AgentTaskEntity();
        applyScenarioSnapshot(task, definition);
        task.setPremise(premise);
        applyPremiseSnapshot(task, premise, definition.getCode(), premisePromptText);
        task.setOwner(owner);
        task.setScenario(definition.getScenario());
        String runtimeCode = latest.getRuntimeCode();
        task.setRuntimeCode(runtimeModeService.requireAvailable(runtimeCode, owner));
        RuntimeModelProfileConfig modelProfile = runtimeModeService.requireAvailableModel(
                runtimeCode, latest.getModelProfileId(), owner);
        applyModelSnapshot(task, modelProfile);
        task.setStatus(TaskStatus.PENDING);
        task.setTitle(buildTitle(definition.getScenario(), definition, taskInput, attachments));
        task.setUserInput(taskInput);
        task.setInputValues(createRequest.getInputValues());
        task.setAttachments(attachments);
        applyCapabilitySnapshot(task, definition);
        task.setRuntimeInstructions(prompt.instructions());
        task.setRuntimeUserMessage(prompt.userMessage());
        task.setPresentations(new LinkedHashSet<>(definition.getPresentations()));
        task.setFinalResponseInstructions(prompt.finalResponseInstructions());
        task.setPrompt(prompt.combined());
        task.setStdoutText("");
        task.setStderrText("");
        task.setResultText("");
        task.setSourceTaskId(latest.getId());
        task.setConversationRootTaskId(rootId);
        task.setRoundNo(rounds.stream().map(AgentTaskEntity::getRoundNo).filter(value -> value != null).max(Integer::compareTo).orElse(1) + 1);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(task.getCreatedAt());
        AgentTaskEntity saved = agentTaskRepository.save(task);
        copyRuntimeContext(latest.getId(), saved);
        touchConversationRoot(rootId, saved.getUpdatedAt());
        log.info("continue task round rootTaskId={} sourceTaskId={} newTaskId={} roundNo={}", rootId, latest.getId(), saved.getId(), saved.getRoundNo());
        runAfterCommit(saved.getId());
        return toResponse(saved, owner);
    }

    @Transactional(readOnly = true)
    public List<TaskRoundSummaryResponse> rounds(Long id, UserEntity currentUser) {
        AgentTaskEntity task = requireAccessibleEntity(id, currentUser);
        Long rootId = task.getConversationRootTaskId() == null ? task.getId() : task.getConversationRootTaskId();
        List<AgentTaskEntity> rounds = conversationRounds(rootId).stream()
                .filter(round -> canAccess(round, currentUser))
                .toList();
        return rounds.stream().map(round -> {
            TaskRoundSummaryResponse response = roundSummary(round);
            response.setResourceMemoryMetrics(taskMetricsService.resourceMemoryMetrics(round));
            response.setExecutionMetrics(taskMetricsService.resolvedExecutionMetrics(round));
            return response;
        }).toList();
    }

    List<AgentTaskEntity> conversationRounds(Long rootId) {
        Map<Long, AgentTaskEntity> result = new LinkedHashMap<>();
        agentTaskRepository.findWithDetailsById(rootId).ifPresent(root -> result.put(root.getId(), root));
        for (AgentTaskEntity round : agentTaskRepository.findByConversationRootTaskIdOrderByRoundNoAscCreatedAtAsc(rootId)) {
            result.put(round.getId(), round);
        }
        return result.values().stream()
                .sorted((left, right) -> {
                    int roundCompare = Integer.compare(left.getRoundNo() == null ? 1 : left.getRoundNo(), right.getRoundNo() == null ? 1 : right.getRoundNo());
                    return roundCompare != 0 ? roundCompare : Long.compare(left.getId(), right.getId());
                })
                .toList();
    }

    private void copyRuntimeContext(Long sourceTaskId, AgentTaskEntity target) {
        List<TaskRuntimeContextValueEntity> values = taskRuntimeContextValueRepository.findByTaskIdOrderByContextKeyAsc(sourceTaskId);
        if (values.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (TaskRuntimeContextValueEntity value : values) {
            TaskRuntimeContextValueEntity copied = new TaskRuntimeContextValueEntity();
            copied.setTask(target);
            copied.setContextKey(value.getContextKey());
            copied.setContextValue(value.getContextValue());
            copied.setCreatedAt(now);
            copied.setUpdatedAt(now);
            taskRuntimeContextValueRepository.save(copied);
        }
    }

    private void applyModelSnapshot(AgentTaskEntity task, RuntimeModelProfileConfig profile) {
        task.setModelProfileId(profile.getId());
        task.setModelProviderId(profile.getProviderId());
        task.setModelProviderName(profile.getProviderName());
        task.setModelName(profile.getName());
        task.setModelIdentifier(profile.getModel());
        task.setModelProtocol(profile.getProtocol());
        task.setModelContextWindowTokens(profile.getContextWindowTokens());
        task.setModelReasoningEffort(profile.getReasoningEffort());
        task.setModelInstructionPrompt(profile.getInstructionPrompt() == null ? "" : profile.getInstructionPrompt());
        task.setModelPricing(modelCatalogService.pricingForModel(task.getOwner(), profile.getId()).orElse(null));
        task.setModelImageInputSupported(profile.isImageInputSupported());
    }

    /**
     * 将参数裁剪后的能力契约固化到任务，避免运行期间受场景后续编辑影响。
     *
     * @param task 待保存的任务实体
     * @param definition 已按本次任务参数激活能力的定义
     * @return 无返回值
     */
    private void applyCapabilitySnapshot(AgentTaskEntity task, AgentTaskDefinition definition) {
        task.setEnabledCapabilityCodes(new LinkedHashSet<>(definition.getCapabilities()));
        task.setEnabledCapabilityCommands(copyCapabilityCommands(definition.getCapabilityCommands()));
    }

    /**
     * 将本次使用的场景展示和执行元数据固化到任务，后续安装包更新不会改变已创建任务。
     *
     * @param task 待保存的任务
     * @param definition 当前从安装目录读取的场景定义
     * @return 无返回值
     */
    private void applyScenarioSnapshot(AgentTaskEntity task, AgentTaskDefinition definition) {
        task.setScenarioCode(definition.getCode());
        task.setScenarioName(definition.getName());
        task.setScenarioIcon(definition.getIcon());
        task.setScenarioColor(definition.getColor());
        task.setResultFormat(TextKit.blankToNull(definition.getResultFormat()) == null
                ? "markdown-sections" : definition.getResultFormat());
        task.setResultRenderer(TextKit.blankToNull(definition.getResultRenderer()));
        task.setQueuePriority(definition.getQueuePriority() == null ? 100 : definition.getQueuePriority());
    }

    /**
     * 将场景能力收敛为当前数据库已启用且已安装 Skill 文件的能力，并同步裁剪命令白名单。
     *
     * @param definition 当前任务的场景定义副本
     * @return 无返回值
     */
    private void retainAvailableCapabilities(AgentTaskDefinition definition) {
        Set<String> availableCodes = agentCapabilityService.availableRuntimeCodes(definition.getCapabilities());
        definition.setCapabilities(new LinkedHashSet<>(availableCodes));
        if (definition.getCapabilityCommands() == null) {
            return;
        }
        Map<String, Set<String>> availableCommands = new LinkedHashMap<>();
        definition.getCapabilityCommands().forEach((code, commands) -> {
            if (availableCodes.contains(code)) {
                availableCommands.put(code, commands == null ? new LinkedHashSet<>() : new LinkedHashSet<>(commands));
            }
        });
        definition.setCapabilityCommands(availableCommands);
    }

    private Map<String, Set<String>> copyCapabilityCommands(Map<String, Set<String>> source) {
        if (source == null) {
            return null;
        }
        Map<String, Set<String>> result = new LinkedHashMap<>();
        source.forEach((capabilityCode, commandCodes) -> result.put(capabilityCode,
                commandCodes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(commandCodes)));
        return result;
    }

    private void touchConversationRoot(Long rootId, LocalDateTime updatedAt) {
        if (rootId == null) {
            return;
        }
        agentTaskRepository.touchConversationRootUpdatedAt(rootId, updatedAt == null ? LocalDateTime.now() : updatedAt);
    }

    /**
     * 分页查询任务。
     *
     * @param page 页码，从 1 开始
     * @param size 每页数量
     * @param scenarioValue 任务场景，可为空
     * @param statusValue 任务状态，可为空
     * @param recordType 记录类型，可为空；analysis 表示只查用户分析记录
     * @param scope 查询范围，可为空；mine 表示只查当前用户
     * @param query 标题或提问内容关键字，可为空
     * @param ownerId 提问人 ID，可为空，仅管理员筛选使用
     * @param createdStart 创建开始时间，可为空
     * @param createdEnd 创建结束时间，可为空
     * @param runtimeCode Runtime 编码，可为空
     * @param modelProfileId 模型配置 ID，可为空
     * @param sortBy 排序字段，可为空；支持 updatedAt、createdAt，默认 updatedAt
     * @param aggregateConversation 是否按会话聚合，可为空；默认聚合
     * @param currentUser 当前登录用户
     * @return 任务分页
     */
    @Transactional(readOnly = true)
    public PageResult<TaskResponse> page(int page, int size, String scenarioValue, String statusValue, String recordType, String scope,
                                         String query, Long ownerId, String createdStart, String createdEnd, String runtimeCodeValue,
                                         String modelProfileIdValue, String sortBy, Boolean aggregateConversation, UserEntity currentUser) {
        PageRequest pageRequest = PageRequest.of(Math.max(page - 1, 0), size, taskSort(sortBy));
        String scenario = TextKit.blankToNull(scenarioValue);
        String runtimeCode = TextKit.blankToNull(runtimeCodeValue);
        String modelProfileId = TextKit.blankToNull(modelProfileIdValue);
        TaskStatus status = TextKit.blankToNull(statusValue) == null ? null : TaskStatus.parse(statusValue);
        Boolean analysisScope = analysisScope(recordType);
        boolean mineScope = mineScope(scope);
        boolean aggregate = aggregateConversation == null || aggregateConversation;
        Page<AgentTaskEntity> result = agentTaskRepository.findAll(taskSpecification(currentUser, scenario, status,
                analysisScope, mineScope, query, ownerId, createdStart, createdEnd, runtimeCode, modelProfileId,
                aggregate), pageRequest);
        return new PageResult<>(result.getContent().stream().map(item -> toListResponse(item, currentUser, aggregate)).toList(), result.getTotalElements(), page, size);
    }

    private Sort taskSort(String sortBy) {
        String value = TextKit.blankToNull(sortBy);
        if (value == null || "updatedAt".equals(value)) {
            return Sort.by(Sort.Direction.DESC, "updatedAt").and(Sort.by(Sort.Direction.DESC, "id"));
        }
        if ("createdAt".equals(value)) {
            return Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));
        }
        throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "排序字段不支持");
    }

    private Specification<AgentTaskEntity> taskSpecification(UserEntity currentUser, String scenario, TaskStatus status, Boolean analysisScope,
                                                            boolean mineScope, String query, Long ownerId, String createdStart, String createdEnd,
                                                            String runtimeCode, String modelProfileId,
                                                            boolean aggregateConversation) {
        return (root, criteriaQuery, criteriaBuilder) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            if (mineScope || !isAdmin(currentUser)) {
                predicates.add(criteriaBuilder.equal(root.get("owner").get("id"), currentUser.getId()));
            } else if (ownerId != null) {
                predicates.add(criteriaBuilder.equal(root.get("owner").get("id"), ownerId));
            }
            if (scenario != null) {
                predicates.add(criteriaBuilder.equal(root.get("scenario"), scenario));
            }
            if (aggregateConversation) {
                Path<Long> conversationRootTaskId = root.get("conversationRootTaskId");
                Path<Long> taskId = root.get("id");
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.isNull(conversationRootTaskId),
                        criteriaBuilder.equal(conversationRootTaskId, taskId)
                ));
            }
            if (analysisScope != null) {
                predicates.add(analysisScope
                        ? criteriaBuilder.isNotNull(root.get("scenarioCode"))
                        : criteriaBuilder.isNull(root.get("scenarioCode")));
            }
            if (status != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), status));
            }
            if (runtimeCode != null) {
                predicates.add(criteriaBuilder.equal(root.get("runtimeCode"), runtimeCode));
            }
            if (modelProfileId != null) {
                predicates.add(criteriaBuilder.equal(root.get("modelProfileId"), modelProfileId));
            }
            String keyword = TextKit.blankToNull(query);
            if (keyword != null) {
                String pattern = "%" + keyword.toLowerCase() + "%";
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.like(root.get("userInput").as(String.class), "%" + keyword + "%"),
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("title")), pattern)
                ));
            }
            LocalDateTime startTime = parseDateTime(createdStart);
            if (startTime != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("createdAt"), startTime));
            }
            LocalDateTime endTime = parseDateTime(createdEnd);
            if (endTime != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("createdAt"), endTime));
            }
            return criteriaBuilder.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    private boolean mineScope(String scope) {
        String value = TextKit.blankToNull(scope);
        if (value == null) {
            return false;
        }
        if (!"mine".equals(value)) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "查询范围不支持");
        }
        return true;
    }

    private LocalDateTime parseDateTime(String value) {
        String text = TextKit.blankToNull(value);
        if (text == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "时间格式不正确");
        }
    }

    private UserResponse toOwnerResponse(UserEntity entity) {
        UserResponse response = new UserResponse();
        response.setId(entity.getId());
        response.setUsername(entity.getUsername());
        response.setDisplayName(entity.getDisplayName());
        return response;
    }

    private Boolean analysisScope(String recordType) {
        String value = TextKit.blankToNull(recordType);
        if (value == null) {
            return null;
        }
        if (!"analysis".equals(value)) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "记录类型不支持");
        }
        return true;
    }

    /**
     * 查询任务详情。
     *
     * @param id 任务 ID
     * @return 任务详情
     * @throws BizException 任务不存在时抛出
     */
    @Transactional(readOnly = true)
    public TaskResponse detail(Long id, UserEntity currentUser) {
        return toResponse(requireAccessibleEntity(id, currentUser), currentUser, true);
    }

    @Transactional(readOnly = true)
    public TaskResponse detail(Long id, UserEntity currentUser, boolean includeEvents) {
        return toResponse(requireAccessibleEntity(id, currentUser), currentUser, includeEvents);
    }

    /**
     * 校验当前用户是否可以查看任务执行过程。
     *
     * @param id 任务 ID
     * @param currentUser 当前登录用户
     * @return 任务实体
     * @throws BizException 任务不存在、无任务访问权限或无过程查看权限时抛出
     */
    public AgentTaskEntity requireProcessAccessibleEntity(Long id, UserEntity currentUser) {
        AgentTaskEntity task = requireAccessibleEntity(id, currentUser);
        if (!isAdmin(currentUser)) {
            throw new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.FORBIDDEN, "没有查看执行过程权限");
        }
        return task;
    }

    /**
     * 取消运行中的任务。
     *
     * @param id 任务 ID
     * @return 任务详情
     * @throws BizException 任务不存在或未运行时抛出
     */
    public TaskResponse cancel(Long id, UserEntity currentUser) {
        TaskResponse response = transactionTemplate.execute(status -> {
            AgentTaskEntity task = requireAccessibleEntity(id, currentUser);
            if (task.getStatus() != TaskStatus.RUNNING && task.getStatus() != TaskStatus.PENDING && task.getStatus() != TaskStatus.WAITING_USER) {
                throw new BizException(ErrorCode.TASK_ERROR, ErrorSubCode.TASK_NOT_RUNNING);
            }
            task.setStatus(TaskStatus.CANCELED);
            task.setStderrText(taskContentService.write(id, "stderr", ""));
            task.setResultText(taskContentService.write(id, "result", ""));
            task.setResultData(null);
            task.setEndedAt(LocalDateTime.now());
            taskMetricsService.captureExecutionMetrics(task);
            task.setUpdatedAt(task.getEndedAt());
            AgentTaskEntity saved = agentTaskRepository.save(task);
            touchConversationRoot(saved.getConversationRootTaskId(), saved.getUpdatedAt());
            return toResponse(saved, currentUser, true);
        });
        taskRunner.cancel(id);
        taskEventStreamService.complete(id);
        return response;
    }

    /**
     * 重新执行失败任务，保留原问题、参数和会话轮次，并按当前场景与能力安装状态刷新执行契约。
     *
     * @param id 失败任务 ID
     * @param currentUser 当前登录用户
     * @return 已重新进入队列的任务详情
     * @throws BizException 任务不存在、无权限或任务状态不是失败时抛出
     */
    @Transactional
    public TaskResponse retry(Long id, UserEntity currentUser) {
        AgentTaskEntity task = requireAccessibleEntity(id, currentUser);
        if (task.getStatus() != TaskStatus.FAILED) {
            throw new BizException(ErrorCode.TASK_ERROR, ErrorSubCode.TASK_NOT_RUNNING, "只有失败任务可以重试");
        }
        String runtimeCode = task.getRuntimeCode();
        task.setRuntimeCode(runtimeModeService.requireAvailable(runtimeCode, task.getOwner()));
        RuntimeModelProfileConfig modelProfile = runtimeModeService.requireAvailableModel(
                runtimeCode, task.getModelProfileId(), task.getOwner());
        applyModelSnapshot(task, modelProfile);
        refreshExecutionDefinition(task, task.getSourceTaskId());
        userUsageQuotaService.requireAvailable(task.getOwner());

        taskEventService.clear(id);
        taskInteractionService.clear(id);
        taskContentService.clear(id);

        LocalDateTime now = LocalDateTime.now();
        task.setStatus(TaskStatus.PENDING);
        task.setStdoutText("");
        task.setStderrText("");
        task.setResultText("");
        task.setResultData(null);
        task.setExitCode(null);
        task.setRuntimeSessionId(null);
        task.setRuntimeSessionPath(null);
        task.setRequestCount(null);
        task.setInputTokens(null);
        task.setCachedInputTokens(null);
        task.setCacheCreationInputTokens(null);
        task.setOutputTokens(null);
        task.setReasoningOutputTokens(null);
        task.setTotalTokens(null);
        task.setResourceMemorySearchCount(null);
        task.setResourceMemoryHitCount(null);
        task.setResourceMemoryCandidateCount(null);
        task.setResourceMemorySaveCount(null);
        task.setResourceMemoryCreatedCount(null);
        task.setResourceMemoryRefreshedCount(null);
        task.setResourceMemoryExpiredCount(null);
        task.setResourceMemoryInvalidatedCount(null);
        task.setExecutionFirstFeedbackMs(null);
        task.setExecutionCommandDurationMs(null);
        task.setExecutionCompactionCount(null);
        task.setExecutionDuplicateCapabilityCallCount(null);
        task.setStartedAt(null);
        task.setEngineCompletedAt(null);
        task.setEndedAt(null);
        task.setUpdatedAt(now);
        AgentTaskEntity saved = agentTaskRepository.save(task);
        touchConversationRoot(saved.getConversationRootTaskId(), now);
        log.info("重试失败任务 taskId={} roundNo={} owner={}", id, saved.getRoundNo(), currentUser == null ? null : currentUser.getUsername());
        runAfterCommit(id);
        return toResponse(saved, currentUser, true);
    }

    /**
     * 恢复用户手动取消的当前轮。输入未修改时续接原执行，修改后原地重跑当前轮。
     *
     * @param id 已取消任务 ID
     * @param request 可选的本轮新输入；未提供表示继续原执行
     * @param currentUser 当前登录用户
     * @return 已重新进入队列的任务详情
     * @throws BizException 任务不存在、无权限、状态不是已取消、执行配置不可用或旧执行尚未停止时抛出
     */
    public TaskResponse resumeCanceled(Long id, TaskResumeRequest request, UserEntity currentUser) {
        AgentTaskEntity canceledTask = requireAccessibleEntity(id, currentUser);
        if (canceledTask.getStatus() != TaskStatus.CANCELED) {
            throw new BizException(ErrorCode.TASK_ERROR, ErrorSubCode.TASK_NOT_RUNNING, "只有手动取消的任务可以恢复");
        }
        runtimeModeService.requireAvailableModel(
                canceledTask.getRuntimeCode(), canceledTask.getModelProfileId(), canceledTask.getOwner());
        userUsageQuotaService.requireAvailable(canceledTask.getOwner());
        try {
            if (!taskRunner.awaitStopped(id, CANCELED_TASK_STOP_WAIT_MILLIS)) {
                throw new BizException(ErrorCode.TASK_ERROR, ErrorSubCode.TASK_NOT_RUNNING,
                        "任务仍在停止中，请稍后再恢复");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.PROCESS_ERROR, ErrorSubCode.RUNTIME_EXEC_FAILED,
                    "等待任务停止时被中断，请稍后再恢复");
        }
        return transactionTemplate.execute(status -> {
            AgentTaskEntity task = requireAccessibleEntity(id, currentUser);
            if (task.getStatus() != TaskStatus.CANCELED) {
                throw new BizException(ErrorCode.TASK_ERROR, ErrorSubCode.TASK_NOT_RUNNING,
                        "任务状态已变化，请刷新后重试");
            }
            String requestedInput = request == null ? null : request.getUserInput();
            String revisedInput = requestedInput == null ? null : requestedInput.trim();
            boolean inputChanged = revisedInput != null && !revisedInput.equals(task.getUserInput());

            if (inputChanged) {
                AgentTaskDefinition definition = agentScenarioService.requireTaskDefinition(task.getScenarioCode());
                List<TaskInputValue> inputValues = task.getInputValues() == null ? new ArrayList<>()
                        : task.getInputValues().stream().map(inputValue -> {
                            TaskInputValue copied = new TaskInputValue();
                            copied.setKey(inputValue.getKey());
                            copied.setValue("userInput".equals(inputValue.getKey()) ? revisedInput : inputValue.getValue());
                            return copied;
                        }).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                if (inputValues.stream().noneMatch(inputValue -> "userInput".equals(inputValue.getKey()))) {
                    TaskInputValue userInputValue = new TaskInputValue();
                    userInputValue.setKey("userInput");
                    userInputValue.setValue(revisedInput);
                    inputValues.add(userInputValue);
                }
                List<TaskAttachmentResponse> retainedAttachments = task.getAttachments() == null ? List.of()
                        : task.getAttachments().stream()
                        .filter(attachment -> !TaskAttachmentService.USER_INPUT_KIND.equals(attachment.getInputKind()))
                        .toList();
                TaskCreateRequest revisedRequest = new TaskCreateRequest();
                revisedRequest.setUserInput(revisedInput);
                revisedRequest.setInputValues(inputValues);
                revisedRequest.setAttachmentIds(retainedAttachments.stream().map(TaskAttachmentResponse::getId).toList());
                String normalizedInput = normalizeUserInput(revisedRequest, definition);
                validateInputSize(revisedRequest);
                PreparedUserInput preparedInput = prepareUserInput(
                        normalizedInput, revisedRequest, retainedAttachments, task.getOwner());

                task.setUserInput(preparedInput.userInput());
                task.setInputValues(revisedRequest.getInputValues());
                task.setAttachments(preparedInput.attachments());
                task.setTitle(buildTitle(task.getScenario(), definition, task.getUserInput(), task.getAttachments()));
                refreshExecutionDefinition(task, task.getSourceTaskId());

                // 修改输入代表重跑当前轮：只保留上一轮关系，不保留本轮取消前产生的执行上下文。
                taskRunner.clearWorkspace(id);
                taskEventService.clear(id);
                taskInteractionService.clear(id);
                taskContentService.clear(id);
                taskRuntimeContextValueRepository.deleteByTaskId(id);
                if (task.getSourceTaskId() != null) {
                    copyRuntimeContext(task.getSourceTaskId(), task);
                }
                task.setStdoutText("");
                task.setRuntimeSessionId(null);
                task.setRuntimeSessionPath(null);
                task.setRequestCount(null);
                task.setInputTokens(null);
                task.setCachedInputTokens(null);
                task.setCacheCreationInputTokens(null);
                task.setOutputTokens(null);
                task.setReasoningOutputTokens(null);
                task.setTotalTokens(null);
                task.setResourceMemorySearchCount(null);
                task.setResourceMemoryHitCount(null);
                task.setResourceMemoryCandidateCount(null);
                task.setResourceMemorySaveCount(null);
                task.setResourceMemoryCreatedCount(null);
                task.setResourceMemoryRefreshedCount(null);
                task.setResourceMemoryExpiredCount(null);
                task.setResourceMemoryInvalidatedCount(null);
                task.setStartedAt(null);
            }
            LocalDateTime now = LocalDateTime.now();
            task.setStatus(TaskStatus.PENDING);
            task.setStderrText(taskContentService.write(id, "stderr", ""));
            task.setResultText(taskContentService.write(id, "result", ""));
            task.setResultData(null);
            task.setExitCode(null);
            task.setEngineCompletedAt(null);
            task.setExecutionFirstFeedbackMs(null);
            task.setExecutionCommandDurationMs(null);
            task.setExecutionCompactionCount(null);
            task.setExecutionDuplicateCapabilityCallCount(null);
            task.setEndedAt(null);
            task.setUpdatedAt(now);
            AgentTaskEntity saved = agentTaskRepository.save(task);
            touchConversationRoot(saved.getConversationRootTaskId(), now);
            log.info("恢复手动取消任务 taskId={} roundNo={} inputChanged={} owner={}", id, saved.getRoundNo(),
                    inputChanged, currentUser == null ? null : currentUser.getUsername());
            runAfterCommit(id);
            return toResponse(saved, currentUser, true);
        });
    }

    /**
     * 使用指定执行模式把失败记录重新创建为独立会话。
     *
     * @param id 失败任务 ID
     * @param request 新执行模式
     * @param currentUser 当前用户
     * @return 新会话的首轮任务
     * @throws BizException 任务不可访问、状态不为失败或运行时不可用时抛出
     */
    @Transactional
    public TaskResponse rerun(Long id, TaskRerunRequest request, UserEntity currentUser) {
        AgentTaskEntity source = requireAccessibleEntity(id, currentUser);
        if (source.getStatus() != TaskStatus.FAILED) {
            throw new BizException(ErrorCode.TASK_ERROR, ErrorSubCode.TASK_NOT_RUNNING, "只有失败记录可以重新运行");
        }
        String runtimeCode = runtimeModeService.requireAvailable(request.getRuntimeCode(), currentUser);
        RuntimeModelProfileConfig modelProfile = runtimeModeService.requireAvailableModel(
                runtimeCode, request.getModelProfileId(), currentUser);
        userUsageQuotaService.requireAvailable(currentUser);

        // 新记录保留用户输入与参数，但按当前场景重新生成执行契约，不继承失败时的能力快照。
        AgentTaskEntity task = new AgentTaskEntity();
        task.setScenarioCode(source.getScenarioCode());
        task.setPremise(source.getPremise());
        task.setOwner(currentUser);
        task.setRuntimeCode(runtimeCode);
        applyModelSnapshot(task, modelProfile);
        task.setStatus(TaskStatus.PENDING);
        task.setTitle(source.getTitle());
        task.setUserInput(source.getUserInput());
        task.setInputValues(source.getInputValues());
        task.setAttachments(source.getAttachments());
        refreshExecutionDefinition(task, null);
        task.setStdoutText("");
        task.setStderrText("");
        task.setResultText("");
        task.setSourceTaskId(null);
        task.setRoundNo(1);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(task.getCreatedAt());
        AgentTaskEntity saved = agentTaskRepository.save(task);
        saved.setConversationRootTaskId(saved.getId());
        saved = agentTaskRepository.save(saved);
        log.info("rerun failed task sourceTaskId={} newTaskId={} runtimeCode={} owner={}", source.getId(), saved.getId(),
                runtimeCode, currentUser == null ? null : currentUser.getUsername());
        runAfterCommit(saved.getId());
        return toResponse(saved, currentUser);
    }

    /**
     * 根据当前场景、分析情境和 Skill 安装状态刷新任务的 Prompt 与能力快照。
     *
     * @param task 待执行或重新执行的任务
     * @param sourceTaskId 需要写入用户消息的来源任务 ID；独立重新运行时为空
     * @return 无返回值
     * @throws BizException 任务没有场景定义时抛出
     */
    private void refreshExecutionDefinition(AgentTaskEntity task, Long sourceTaskId) {
        if (TextKit.blankToNull(task.getScenarioCode()) == null) {
            throw new BizException(ErrorCode.TASK_ERROR, ErrorSubCode.VALIDATION_FAILED,
                    "任务缺少场景定义，不能重新执行");
        }
        agentScenarioService.requireEntity(task.getScenarioCode());
        AgentTaskDefinition definition = agentScenarioService.requireTaskDefinition(task.getScenarioCode());
        definition.activateCapabilities(task.getInputValues());
        retainAvailableCapabilities(definition);

        TaskCreateRequest request = new TaskCreateRequest();
        request.setScenarioCode(definition.getCode());
        request.setPremiseId(task.getPremise() == null ? null : task.getPremise().getId());
        request.setSourceTaskId(sourceTaskId);
        request.setUserInput(task.getUserInput());
        request.setInputValues(task.getInputValues());
        String premisePromptText = analysisPremiseService.premisePromptText(task.getPremise(), definition.getCode());
        TaskPrompt prompt = promptService.buildTaskPrompt(
                definition.getScenario(), definition, request, premisePromptText);

        applyScenarioSnapshot(task, definition);
        task.setScenario(definition.getScenario());
        applyPremiseSnapshot(task, task.getPremise(), definition.getCode(), premisePromptText);
        applyCapabilitySnapshot(task, definition);
        task.setRuntimeInstructions(prompt.instructions());
        task.setRuntimeUserMessage(prompt.userMessage());
        task.setPresentations(new LinkedHashSet<>(definition.getPresentations()));
        task.setFinalResponseInstructions(prompt.finalResponseInstructions());
        task.setPrompt(prompt.combined());
    }

    private AgentTaskEntity requireEntity(Long id) {
        return agentTaskRepository.findWithDetailsById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.TASK_NOT_FOUND));
    }

    public AgentTaskEntity requireAccessibleEntity(Long id, UserEntity currentUser) {
        AgentTaskEntity task = requireEntity(id);
        if (!canAccess(task, currentUser)) {
            throw new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.FORBIDDEN, "没有操作权限");
        }
        return task;
    }

    private boolean sourceTaskExists(Long sourceTaskId, String scenarioCode) {
        return agentTaskRepository.existsBySourceTaskIdAndScenarioCode(sourceTaskId, scenarioCode);
    }

    private TaskResponse detailBySourceTask(Long sourceTaskId, String scenarioCode, UserEntity currentUser) {
        return agentTaskRepository.findFirstBySourceTaskIdAndScenarioCodeOrderByCreatedAtDesc(sourceTaskId, scenarioCode)
                .filter(task -> canAccess(task, currentUser))
                .map(task -> toResponse(task, currentUser))
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.TASK_NOT_FOUND));
    }

    private void validatePremiseScenarioAccess(AnalysisPremiseEntity premise, String scenarioCode) {
        if (premise == null || premise.getVisibleScenarioCodes() == null || premise.getVisibleScenarioCodes().isEmpty()) {
            return;
        }
        if (!premise.getVisibleScenarioCodes().contains(scenarioCode)) {
            throw new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.FORBIDDEN, "当前情境不可使用该场景");
        }
    }

    private String buildTitle(String scenario, AgentTaskDefinition definition, String input, List<TaskAttachmentResponse> attachments) {
        String normalizedInput = TextKit.blankToNull(input);
        if (normalizedInput != null) {
            return TextKit.limit(normalizedInput.trim().replaceAll("\\s+", " "), MAX_TASK_TITLE_LENGTH);
        }
        if (attachments != null && !attachments.isEmpty()) {
            return TextKit.limit(attachments.get(0).getName(), MAX_TASK_TITLE_LENGTH);
        }
        return TextKit.limit(definition == null ? scenario : definition.getName(), MAX_TASK_TITLE_LENGTH);
    }

    /**
     * 超长输入自动保存为全文附件，并将任务输入和同名结构化参数替换为短摘要。
     *
     * @param userInput 已规范化的用户输入
     * @param request 当前任务创建参数
     * @param attachments 已校验的用户附件
     * @param owner 当前登录用户
     * @return 提交给任务模型的摘要和包含全文文件的附件列表
     * @throws BizException 全文附件保存失败时抛出
     */
    private PreparedUserInput prepareUserInput(String userInput, TaskCreateRequest request,
                                               List<TaskAttachmentResponse> attachments, UserEntity owner) {
        if (userInput == null || userInput.length() <= LONG_INPUT_THRESHOLD) {
            return new PreparedUserInput(userInput == null ? "" : userInput, attachments);
        }
        String excerpt = userInput.substring(0, Math.min(userInput.length(), LONG_INPUT_EXCERPT_LENGTH)).stripTrailing() + "…";
        TaskAttachmentResponse fullInputAttachment = taskAttachmentService.createUserInputAttachment(userInput, owner);
        List<TaskAttachmentResponse> nextAttachments = new ArrayList<>(attachments == null ? List.of() : attachments);
        nextAttachments.removeIf(attachment -> TaskAttachmentService.USER_INPUT_KIND.equals(attachment.getInputKind()));
        nextAttachments.add(fullInputAttachment);
        if (request.getInputValues() != null) {
            request.getInputValues().stream()
                    .filter(inputValue -> inputValue != null && "userInput".equals(inputValue.getKey()))
                    .forEach(inputValue -> inputValue.setValue(excerpt));
        }
        log.info("任务超长输入已转换为全文附件 ownerId={} inputLength={} attachmentId={}",
                owner.getId(), userInput.length(), fullInputAttachment.getId());
        return new PreparedUserInput(excerpt, nextAttachments);
    }

    private record PreparedUserInput(String userInput, List<TaskAttachmentResponse> attachments) {
    }

    private void runAfterCommit(Long taskId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            submitTaskSafely(taskId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submitTaskSafely(taskId);
            }
        });
    }

    private void submitTaskSafely(Long taskId) {
        try {
            taskRunner.submit(taskId);
        } catch (Exception e) {
            log.info("提交任务到后台队列失败，等待队列维护线程恢复 taskId={} message={}", taskId, e.getMessage());
        }
    }

    private TaskResponse toResponse(AgentTaskEntity entity, UserEntity currentUser) {
        return toResponse(entity, currentUser, true);
    }

    private TaskResponse toListResponse(AgentTaskEntity entity, UserEntity currentUser, boolean aggregateConversation) {
        TaskResponse response = baseResponse(entity);
        response.setUserInput(TextKit.limit(entity.getUserInput(), 300));
        response.setInputValues(java.util.List.of());
        response.setAttachments(java.util.List.of());
        response.setResultText("");
        response.setEvents(java.util.List.of());
        response.setEventEntries(java.util.List.of());
        response.setInteractions(java.util.List.of());
        List<AgentTaskEntity> rounds = applyRoundSummaries(response, entity);
        if (aggregateConversation) {
            applyLatestRoundListState(response, rounds);
        }
        if (canViewProcessOutput(currentUser)) {
            response.setStdoutText("");
            response.setStderrText("");
        }
        return response;
    }

    private TaskResponse toResponse(AgentTaskEntity entity, UserEntity currentUser, boolean includeEvents) {
        TaskResponse response = baseResponse(entity);
        response.setResourceMemoryMetrics(taskMetricsService.resourceMemoryMetrics(entity));
        response.setExecutionMetrics(taskMetricsService.resolvedExecutionMetrics(entity));
        response.setUserInput(entity.getUserInput());
        response.setInputValues(entity.getInputValues());
        response.setAttachments(entity.getAttachments() == null ? java.util.List.of() : entity.getAttachments());
        response.setResultText(taskContentService.read(entity.getId(), "result", entity.getResultText()));
        response.setResultData(entity.getResultData());
        response.setInteractions(includeEvents ? taskInteractionService.list(entity.getId()) : java.util.List.of());
        if (canViewProcessOutput(currentUser)) {
            response.setPrompt(entity.getPrompt());
            response.setStdoutText(taskContentService.read(entity.getId(), "stdout", entity.getStdoutText()));
            response.setStderrText(taskContentService.read(entity.getId(), "stderr", entity.getStderrText()));
            response.setEvents(includeEvents ? taskEventService.list(entity.getId()) : java.util.List.of());
            response.setEventEntries(includeEvents ? taskEventService.listEntries(entity.getId(), isActiveTask(entity.getStatus())) : java.util.List.of());
        } else {
            response.setEvents(includeEvents ? taskEventService.list(entity.getId(), false) : java.util.List.of());
            response.setEventEntries(java.util.List.of());
        }
        return response;
    }

    private boolean isActiveTask(TaskStatus status) {
        return status == TaskStatus.PENDING || status == TaskStatus.RUNNING || status == TaskStatus.WAITING_USER;
    }

    private TaskResponse baseResponse(AgentTaskEntity entity) {
        TaskResponse response = new TaskResponse();
        response.setId(entity.getId());
        if (entity.getOwner() != null) {
            response.setOwnerUserId(entity.getOwner().getId());
            response.setOwnerUsername(entity.getOwner().getUsername());
            response.setOwnerDisplayName(entity.getOwner().getDisplayName());
        }
        if (entity.getScenarioCode() != null) {
            response.setScenarioCode(entity.getScenarioCode());
            response.setScenarioName(entity.getScenarioName());
            response.setScenarioIconUrl(DefinitionAssetUrlKit.iconUrl("scenarios", entity.getScenarioCode(),
                    entity.getScenarioIcon(), entity.getUpdatedAt()));
            response.setScenarioColor(entity.getScenarioColor());
            response.setResultRenderer(entity.getResultRenderer());
        }
        response.setRecommendedScenarioCodes(taskRecommendedScenarioCodes(entity));
        if (entity.getPremise() != null) {
            response.setPremiseId(entity.getPremise().getId());
            response.setPremiseName(entity.getPremise().getName());
        }
        response.setPremiseSnapshotName(entity.getPremiseSnapshotName());
        response.setPremiseSnapshotDescription(entity.getPremiseSnapshotDescription());
        response.setPremiseSnapshotContextValues(entity.getPremiseSnapshotContextValues());
        response.setScenario(entity.getScenario());
        response.setRuntimeCode(entity.getRuntimeCode());
        response.setModelProfileId(entity.getModelProfileId());
        response.setModelProviderId(entity.getModelProviderId());
        response.setModelProviderName(entity.getModelProviderName());
        response.setModelName(entity.getModelName());
        response.setModelIdentifier(entity.getModelIdentifier());
        response.setModelProtocol(entity.getModelProtocol());
        response.setModelContextWindowTokens(entity.getModelContextWindowTokens());
        response.setModelReasoningEffort(entity.getModelReasoningEffort());
        response.setStatus(entity.getStatus());
        response.setTitle(entity.getTitle());
        response.setExitCode(entity.getExitCode());
        response.setSourceTaskId(entity.getSourceTaskId());
        response.setConversationRootTaskId(entity.getConversationRootTaskId());
        response.setRoundNo(entity.getRoundNo());
        response.setRequestCount(entity.getRequestCount());
        response.setInputTokens(entity.getInputTokens());
        response.setCachedInputTokens(entity.getCachedInputTokens());
        response.setCacheCreationInputTokens(entity.getCacheCreationInputTokens());
        response.setOutputTokens(entity.getOutputTokens());
        response.setReasoningOutputTokens(entity.getReasoningOutputTokens());
        response.setTotalTokens(entity.getTotalTokens());
        response.setStartedAt(entity.getStartedAt());
        response.setEndedAt(entity.getEndedAt());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        return response;
    }

    private Set<String> copyStringSet(Collection<String> values) {
        return values == null ? new LinkedHashSet<>() : new LinkedHashSet<>(values);
    }

    private Set<String> taskRecommendedScenarioCodes(AgentTaskEntity entity) {
        return entity.getResultData() == null ? new LinkedHashSet<>() : copyStringSet(entity.getResultData().getRecommendedScenarioCodes());
    }

    private List<AgentTaskEntity> applyRoundSummaries(TaskResponse response, AgentTaskEntity entity) {
        Long rootId = entity.getConversationRootTaskId() == null ? entity.getId() : entity.getConversationRootTaskId();
        List<AgentTaskEntity> rounds = conversationRounds(rootId);
        List<TaskRoundSummaryResponse> summaries = rounds.stream()
                .map(this::roundSummary)
                .toList();
        response.setRoundCount(summaries.size());
        response.setRoundSummaries(summaries);
        return rounds;
    }

    private void applyLatestRoundListState(TaskResponse response, List<AgentTaskEntity> rounds) {
        if (rounds == null || rounds.isEmpty()) {
            return;
        }
        AgentTaskEntity latest = rounds.get(rounds.size() - 1);
        response.setStatus(latest.getStatus());
        response.setStartedAt(latest.getStartedAt());
        response.setEndedAt(latest.getEndedAt());
        response.setUpdatedAt(latest.getUpdatedAt());
    }

    private TaskRoundSummaryResponse roundSummary(AgentTaskEntity entity) {
        TaskRoundSummaryResponse response = new TaskRoundSummaryResponse();
        response.setId(entity.getId());
        response.setRuntimeCode(entity.getRuntimeCode());
        response.setModelProfileId(entity.getModelProfileId());
        response.setModelProviderId(entity.getModelProviderId());
        response.setModelProviderName(entity.getModelProviderName());
        response.setModelName(entity.getModelName());
        response.setModelIdentifier(entity.getModelIdentifier());
        response.setModelProtocol(entity.getModelProtocol());
        response.setModelReasoningEffort(entity.getModelReasoningEffort());
        response.setRoundNo(entity.getRoundNo() == null ? 1 : entity.getRoundNo());
        response.setUserInput(TextKit.limit(entity.getUserInput(), 180));
        response.setStatus(entity.getStatus());
        response.setRequestCount(entity.getRequestCount());
        response.setInputTokens(entity.getInputTokens());
        response.setCachedInputTokens(entity.getCachedInputTokens());
        response.setCacheCreationInputTokens(entity.getCacheCreationInputTokens());
        response.setOutputTokens(entity.getOutputTokens());
        response.setReasoningOutputTokens(entity.getReasoningOutputTokens());
        response.setTotalTokens(entity.getTotalTokens());
        response.setStartedAt(entity.getStartedAt());
        response.setEndedAt(entity.getEndedAt());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        return response;
    }

    private void applyPremiseSnapshot(AgentTaskEntity task, AnalysisPremiseEntity premise, String scenarioCode, String premisePromptText) {
        if (premise == null) {
            return;
        }
        task.setPremiseSnapshotName(TextKit.blankToNull(premise.getName()));
        task.setPremiseSnapshotDescription(TextKit.blankToNull(premise.getDescription()));
        task.setPremiseSnapshotPromptText(TextKit.blankToNull(premisePromptText));
        Map<String, String> contextValues = analysisPremiseService.effectiveContextValues(premise, scenarioCode);
        if (contextValues != null && !contextValues.isEmpty()) {
            task.setPremiseSnapshotContextValues(new LinkedHashMap<>(contextValues));
        }
    }

    private String normalizeUserInput(TaskCreateRequest request, AgentTaskDefinition definition) {
        String directInput = TextKit.blankToNull(request.getUserInput());
        if (directInput != null) {
            return directInput.trim();
        }
        String inputValue = firstInputValue(request, false);
        if (inputValue != null) {
            return inputValue;
        }
        if (isUserInputRequired(definition) && (request.getAttachmentIds() == null || request.getAttachmentIds().isEmpty())) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "请输入内容");
        }
        return "";
    }

    private boolean isUserInputRequired(AgentTaskDefinition definition) {
        return isParameterRequired(definition, "userInput");
    }

    private boolean isParameterRequired(AgentTaskDefinition definition, String paramKey) {
        return definition.getParameters().stream()
                .anyMatch(parameter -> paramKey.equals(parameter.key()) && parameter.required());
    }

    private String firstInputValue(TaskCreateRequest request, boolean required) {
        if (request.getInputValues() == null || request.getInputValues().isEmpty()) {
            if (required) {
                throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "请输入内容");
            }
            return null;
        }
        String value = request.getInputValues().stream()
                .filter(item -> "userInput".equals(item.getKey()))
                .map(TaskInputValue::getValue)
                .filter(item -> item != null && !item.isBlank())
                .findFirst()
                .orElse(null);
        if (value == null) {
            if (required) {
                throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "请输入内容");
            }
            return null;
        }
        return value.trim();
    }

    private String inputValue(TaskCreateRequest request, String key) {
        if (request.getInputValues() == null || request.getInputValues().isEmpty()) {
            return null;
        }
        return request.getInputValues().stream()
                .filter(item -> key.equals(item.getKey()))
                .map(TaskInputValue::getValue)
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .findFirst()
                .orElse(null);
    }

    private boolean canAccess(AgentTaskEntity task, UserEntity currentUser) {
        if (isAdmin(currentUser)) {
            return true;
        }
        return task.getOwner() != null && task.getOwner().getId().equals(currentUser.getId());
    }

    private boolean isAdmin(UserEntity user) {
        return user != null && user.getRole() == UserRole.ADMIN;
    }

    public boolean canViewProcessOutput(UserEntity user) {
        return isAdmin(user);
    }

    /**
     * 限制用户输入和动态参数的总大小，避免通过多个合法字段绕过单字段限制放大提示词。
     *
     * @param request 已合并上下文参数的任务请求
     * @return 无返回值
     * @throws BizException 总字符数超过限制时抛出
     */
    private void validateInputSize(TaskCreateRequest request) {
        long total = request.getUserInput() == null ? 0 : request.getUserInput().length();
        if (request.getInputValues() != null) {
            for (TaskInputValue inputValue : request.getInputValues()) {
                if (inputValue == null) {
                    continue;
                }
                total += inputValue.getKey() == null ? 0 : inputValue.getKey().length();
                total += inputValue.getValue() == null ? 0 : inputValue.getValue().length();
                if (total > MAX_TOTAL_INPUT_LENGTH) {
                    throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "任务输入总长度不能超过 100000 个字符");
                }
            }
        }
    }
}
