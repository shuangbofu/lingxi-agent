package top.fusb.lingxi.task;

import top.fusb.lingxi.dto.TaskExecutionEvent;
import top.fusb.lingxi.dto.TaskEventPayload;
import top.fusb.lingxi.dto.TaskInteractionAction;
import top.fusb.lingxi.dto.TaskInteractionAnswerValue;
import top.fusb.lingxi.dto.TaskInteractionField;
import top.fusb.lingxi.dto.TaskInteractionAnswerRequest;
import top.fusb.lingxi.dto.TaskInteractionOption;
import top.fusb.lingxi.dto.TaskInteractionRequest;
import top.fusb.lingxi.dto.TaskInteractionResponse;
import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.entity.TaskInteractionEntity;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.enums.TaskEventStatus;
import top.fusb.lingxi.enums.TaskEventType;
import top.fusb.lingxi.enums.TaskInteractionAnswerAction;
import top.fusb.lingxi.enums.TaskInteractionInputType;
import top.fusb.lingxi.enums.TaskInteractionStatus;
import top.fusb.lingxi.enums.TaskStatus;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.kit.TextKit;
import top.fusb.lingxi.repository.AgentTaskRepository;
import top.fusb.lingxi.repository.TaskInteractionRepository;
import top.fusb.lingxi.runtime.capability.AgentRuntimeCapabilityConfigService;
import top.fusb.lingxi.runtime.capability.dto.AgentRuntimeContextPutRequest;
import top.fusb.lingxi.runtime.capability.dto.AgentRuntimeContextValueResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskInteractionService {

    private static final int DEFAULT_TIMEOUT_SECONDS = 900;
    private static final int MIN_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 3600;

    private final AgentTaskRepository agentTaskRepository;
    private final TaskInteractionRepository taskInteractionRepository;
    private final TaskEventService taskEventService;
    private final TaskEventStreamService taskEventStreamService;
    private final AgentRuntimeCapabilityConfigService agentRuntimeCapabilityConfigService;
    private final TransactionTemplate transactionTemplate;
    private final ConcurrentHashMap<Long, Object> waitLocks = new ConcurrentHashMap<>();

    /**
     * 创建一次任务内用户交互请求。
     *
     * @param taskId 任务 ID
     * @param request 交互问题、输入类型和选项
     * @return 交互请求
     * @throws BizException 任务不存在或已结束时抛出
     */
    @Transactional
    public TaskInteractionResponse create(Long taskId, TaskInteractionRequest request) {
        AgentTaskEntity task = requireTask(taskId);
        if (task.getStatus() != TaskStatus.RUNNING && task.getStatus() != TaskStatus.WAITING_USER) {
            throw new BizException(ErrorCode.TASK_ERROR, ErrorSubCode.TASK_NOT_RUNNING, "任务不在可交互状态");
        }
        TaskInteractionEntity entity = new TaskInteractionEntity();
        entity.setTask(task);
        entity.setQuestion(requireQuestion(request));
        entity.setContent(TextKit.blankToNull(request.getContent()));
        entity.setInputType(TaskInteractionInputType.parse(request.getInputType()));
        entity.setOptions(normalizeOptions(request.getOptions()));
        entity.setActions(normalizeActions(request.getActions()));
        entity.setFields(normalizeFields(request.getFields()));
        entity.setRequired(!Boolean.FALSE.equals(request.getRequired()));
        entity.setPlaceholder(TextKit.blankToNull(request.getPlaceholder()));
        entity.setAnswerHint(TextKit.blankToNull(request.getAnswerHint()));
        entity.setDefaultValue(TextKit.blankToNull(request.getDefaultValue()));
        entity.setContextKey(normalizeContextKey(request.getContextKey()));
        entity.setStatus(TaskInteractionStatus.PENDING);
        entity.setCreatedAt(LocalDateTime.now());
        task.setStatus(TaskStatus.WAITING_USER);
        task.setUpdatedAt(entity.getCreatedAt());
        AgentTaskEntity savedTask = agentTaskRepository.save(task);
        touchConversationRoot(savedTask, savedTask.getUpdatedAt());
        TaskInteractionEntity saved = taskInteractionRepository.save(entity);
        publish(taskId, saved, TaskEventStatus.RUNNING, "等待用户补充信息");
        log.info("创建任务交互请求 taskId={} interactionId={} inputType={}", taskId, saved.getId(), saved.getInputType());
        return toResponse(saved);
    }

    /**
     * 查询任务交互请求。
     *
     * @param taskId 任务 ID
     * @return 交互请求列表
     */
    public List<TaskInteractionResponse> list(Long taskId) {
        return taskInteractionRepository.findTop50ByTaskIdOrderByCreatedAtAsc(taskId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 清除一次失败执行产生的交互请求，不删除已经写入任务运行上下文的用户选择。
     *
     * @param taskId 任务 ID
     * @return 无返回值
     */
    @Transactional
    public void clear(Long taskId) {
        taskInteractionRepository.findTop50ByTaskIdOrderByCreatedAtAsc(taskId)
                .forEach(interaction -> waitLocks.remove(interaction.getId()));
        taskInteractionRepository.deleteByTaskId(taskId);
    }

    /**
     * 查询单个交互请求。
     *
     * @param taskId 任务 ID
     * @param interactionId 交互请求 ID
     * @return 交互请求
     * @throws BizException 交互请求不存在时抛出
     */
    public TaskInteractionResponse detail(Long taskId, Long interactionId) {
        TaskInteractionEntity entity = requireInteraction(taskId, interactionId);
        return toResponse(entity);
    }

    /**
     * 提交用户反馈。
     *
     * @param taskId 任务 ID
     * @param interactionId 交互请求 ID
     * @param request 用户输入或选择
     * @return 交互请求
     * @throws BizException 交互请求不存在或已经处理时抛出
     */
    public TaskInteractionResponse answer(Long taskId, Long interactionId, TaskInteractionAnswerRequest request) {
        TaskInteractionResponse response = transactionTemplate.execute(status -> {
            TaskInteractionEntity entity = requireInteraction(taskId, interactionId);
            if (entity.getStatus() != TaskInteractionStatus.PENDING) {
                throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "该交互已经处理");
            }
            applyAnswer(entity, request);
            AgentTaskEntity task = entity.getTask();
            applyContextAnswer(task.getId(), entity);
            if (task.getStatus() == TaskStatus.WAITING_USER) {
                task.setStatus(TaskStatus.RUNNING);
                task.setUpdatedAt(LocalDateTime.now());
                AgentTaskEntity savedTask = agentTaskRepository.save(task);
                touchConversationRoot(savedTask, savedTask.getUpdatedAt());
            }
            TaskInteractionEntity saved = taskInteractionRepository.save(entity);
            return toResponse(saved);
        });
        Object lock = waitLocks.computeIfAbsent(interactionId, key -> new Object());
        synchronized (lock) {
            lock.notifyAll();
        }
        publish(taskId, requireInteraction(taskId, interactionId), TaskEventStatus.SUCCESS, "用户已补充信息");
        log.info("提交任务交互反馈 taskId={} interactionId={}", taskId, interactionId);
        return response;
    }

    /**
     * 阻塞等待用户反馈，供能力运行时轮询使用。
     *
     * @param taskId 任务 ID
     * @param interactionId 交互请求 ID
     * @param timeoutSeconds 超时时间
     * @return 交互请求
     */
    public TaskInteractionResponse waitForAnswer(Long taskId, Long interactionId, Integer timeoutSeconds) {
        long deadlineNanos = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(normalizeTimeout(timeoutSeconds));
        Object lock = waitLocks.computeIfAbsent(interactionId, key -> new Object());
        while (System.nanoTime() < deadlineNanos) {
            TaskInteractionResponse response = detail(taskId, interactionId);
            if (response.getStatus() != TaskInteractionStatus.PENDING) {
                waitLocks.remove(interactionId);
                return response;
            }
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            long waitMillis = Math.min(3000L, Math.max(200L, remainingMillis));
            synchronized (lock) {
                try {
                    lock.wait(waitMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        expire(taskId, interactionId);
        waitLocks.remove(interactionId);
        return detail(taskId, interactionId);
    }

    private void expire(Long taskId, Long interactionId) {
        transactionTemplate.executeWithoutResult(status -> {
            TaskInteractionEntity entity = requireInteraction(taskId, interactionId);
            if (entity.getStatus() != TaskInteractionStatus.PENDING) {
                return;
            }
            entity.setStatus(TaskInteractionStatus.EXPIRED);
            entity.setAnsweredAt(LocalDateTime.now());
            taskInteractionRepository.save(entity);
            AgentTaskEntity task = entity.getTask();
            if (task.getStatus() == TaskStatus.WAITING_USER) {
                task.setStatus(TaskStatus.RUNNING);
                task.setUpdatedAt(LocalDateTime.now());
                AgentTaskEntity savedTask = agentTaskRepository.save(task);
                touchConversationRoot(savedTask, savedTask.getUpdatedAt());
            }
        });
        publish(taskId, requireInteraction(taskId, interactionId), TaskEventStatus.FAILED, "用户补充信息超时");
    }

    private AgentTaskEntity requireTask(Long taskId) {
        return agentTaskRepository.findWithDetailsById(taskId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.TASK_NOT_FOUND));
    }

    private void touchConversationRoot(AgentTaskEntity task, LocalDateTime updatedAt) {
        Long rootId = task.getConversationRootTaskId();
        if (rootId == null || rootId.equals(task.getId())) {
            return;
        }
        agentTaskRepository.touchConversationRootUpdatedAt(rootId, updatedAt == null ? LocalDateTime.now() : updatedAt);
    }

    private TaskInteractionEntity requireInteraction(Long taskId, Long interactionId) {
        TaskInteractionEntity entity = taskInteractionRepository.findById(interactionId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.TASK_NOT_FOUND, "交互请求不存在"));
        if (!entity.getTask().getId().equals(taskId)) {
            throw new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.TASK_NOT_FOUND, "交互请求不存在");
        }
        return entity;
    }

    private String requireQuestion(TaskInteractionRequest request) {
        String question = request == null ? null : TextKit.blankToNull(request.getQuestion());
        if (question == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "请输入需要用户确认的问题");
        }
        return question;
    }

    private List<TaskInteractionOption> normalizeOptions(List<TaskInteractionOption> options) {
        if (options == null) {
            return List.of();
        }
        return options.stream()
                .filter(option -> option != null && TextKit.blankToNull(option.getLabel()) != null)
                .map(option -> {
                    TaskInteractionOption normalized = new TaskInteractionOption();
                    normalized.setLabel(option.getLabel().trim());
                    normalized.setValue(TextKit.blankToNull(option.getValue()) == null ? option.getLabel().trim() : option.getValue().trim());
                    normalized.setDescription(TextKit.blankToNull(option.getDescription()));
                    return normalized;
                })
                .toList();
    }

    private List<TaskInteractionAction> normalizeActions(List<TaskInteractionAction> actions) {
        if (actions == null) {
            return List.of();
        }
        return actions.stream()
                .filter(action -> action != null && TextKit.blankToNull(action.getKey()) != null && TextKit.blankToNull(action.getLabel()) != null)
                .map(action -> {
                    TaskInteractionAction normalized = new TaskInteractionAction();
                    normalized.setKey(action.getKey().trim());
                    normalized.setLabel(action.getLabel().trim());
                    normalized.setDescription(TextKit.blankToNull(action.getDescription()));
                    normalized.setStyle(TextKit.blankToNull(action.getStyle()));
                    normalized.setValidateInput(Boolean.TRUE.equals(action.getValidateInput()));
                    return normalized;
                })
                .toList();
    }

    private void applyAnswer(TaskInteractionEntity entity, TaskInteractionAnswerRequest request) {
        String rawAction = request == null ? null : TextKit.blankToNull(request.getAction());
        TaskInteractionAnswerAction action = TaskInteractionAnswerAction.parse(rawAction);
        String answerText = request == null ? null : TextKit.blankToNull(request.getAnswerText());
        List<String> selectedValues = request == null || request.getSelectedValues() == null ? List.of() : request.getSelectedValues().stream()
                .filter(value -> TextKit.blankToNull(value) != null)
                .map(String::trim)
                .toList();
        List<TaskInteractionAnswerValue> answerValues = normalizeAnswerValues(request == null ? null : request.getAnswerValues());
        entity.setAnswerAction(action);
        entity.setAnswerActionKey(rawAction == null ? action.name() : rawAction);
        if (action == TaskInteractionAnswerAction.SKIP) {
            entity.setStatus(TaskInteractionStatus.SKIPPED);
            entity.setAnsweredAt(LocalDateTime.now());
            return;
        }
        if (action == TaskInteractionAnswerAction.UNKNOWN) {
            entity.setStatus(TaskInteractionStatus.UNKNOWN);
            entity.setAnsweredAt(LocalDateTime.now());
            return;
        }
        if (action == TaskInteractionAnswerAction.CANCEL) {
            entity.setStatus(TaskInteractionStatus.CANCELED);
            entity.setAnsweredAt(LocalDateTime.now());
            return;
        }
        boolean customAction = rawAction != null && !rawAction.equalsIgnoreCase(action.name());
        boolean validateInput = !customAction || entity.getActions() != null && entity.getActions().stream()
                .anyMatch(item -> rawAction.equals(item.getKey()) && Boolean.TRUE.equals(item.getValidateInput()));
        if (validateInput && Boolean.TRUE.equals(entity.getRequired()) && answerText == null && selectedValues.isEmpty()) {
            if (entity.getInputType() == TaskInteractionInputType.FORM && hasMissingRequiredField(entity.getFields(), answerValues)) {
                throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "请填写或选择后再提交");
            }
        }
        if (validateInput && Boolean.TRUE.equals(entity.getRequired()) && entity.getInputType() != TaskInteractionInputType.FORM && answerText == null && selectedValues.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "请填写或选择后再提交");
        }
        entity.setAnswerText(answerText);
        entity.setSelectedValues(selectedValues);
        entity.setAnswerValues(answerValues);
        entity.setStatus(TaskInteractionStatus.ANSWERED);
        entity.setAnsweredAt(LocalDateTime.now());
    }

    private void applyContextAnswer(Long taskId, TaskInteractionEntity entity) {
        if (entity.getInputType() == TaskInteractionInputType.FORM) {
            applyFormContextAnswer(taskId, entity);
            return;
        }
        String key = entity.getContextKey();
        if (key == null) {
            return;
        }
        String value = contextAnswerValue(entity);
        if (value == null) {
            return;
        }
        AgentRuntimeContextValueResponse contextValue = new AgentRuntimeContextValueResponse();
        contextValue.setKey(key);
        contextValue.setValue(value);
        AgentRuntimeContextPutRequest contextRequest = new AgentRuntimeContextPutRequest();
        contextRequest.setValues(List.of(contextValue));
        agentRuntimeCapabilityConfigService.putContext(taskId, contextRequest);
    }

    private void applyFormContextAnswer(Long taskId, TaskInteractionEntity entity) {
        if (entity.getStatus() != TaskInteractionStatus.ANSWERED || entity.getAnswerValues() == null || entity.getAnswerValues().isEmpty()) {
            return;
        }
        Map<String, TaskInteractionField> fields = (entity.getFields() == null ? List.<TaskInteractionField>of() : entity.getFields()).stream()
                .filter(field -> field != null && TextKit.blankToNull(field.getKey()) != null)
                .collect(Collectors.toMap(TaskInteractionField::getKey, field -> field, (left, right) -> left, LinkedHashMap::new));
        List<AgentRuntimeContextValueResponse> values = entity.getAnswerValues().stream()
                .map(answer -> formContextValue(fields, answer))
                .filter(value -> value != null && TextKit.blankToNull(value.getKey()) != null && TextKit.blankToNull(value.getValue()) != null)
                .toList();
        if (values.isEmpty()) {
            return;
        }
        AgentRuntimeContextPutRequest contextRequest = new AgentRuntimeContextPutRequest();
        contextRequest.setValues(values);
        agentRuntimeCapabilityConfigService.putContext(taskId, contextRequest);
    }

    private AgentRuntimeContextValueResponse formContextValue(Map<String, TaskInteractionField> fields, TaskInteractionAnswerValue answer) {
        if (answer == null || TextKit.blankToNull(answer.getKey()) == null) {
            return null;
        }
        TaskInteractionField field = fields.get(answer.getKey());
        String contextKey = field == null ? answer.getKey() : normalizeContextKey(field.getContextKey() == null ? field.getKey() : field.getContextKey());
        String value = TextKit.blankToNull(answer.getValue());
        if (value == null && answer.getSelectedValues() != null && !answer.getSelectedValues().isEmpty()) {
            value = String.join(",", answer.getSelectedValues());
        }
        if (TextKit.blankToNull(contextKey) == null || TextKit.blankToNull(value) == null) {
            return null;
        }
        AgentRuntimeContextValueResponse contextValue = new AgentRuntimeContextValueResponse();
        contextValue.setKey(contextKey);
        contextValue.setValue(value);
        return contextValue;
    }

    private String contextAnswerValue(TaskInteractionEntity entity) {
        if (entity.getStatus() != TaskInteractionStatus.ANSWERED) {
            return null;
        }
        if (entity.getSelectedValues() != null && !entity.getSelectedValues().isEmpty()) {
            return String.join(",", entity.getSelectedValues());
        }
        return TextKit.blankToNull(entity.getAnswerText());
    }

    private void publish(Long taskId, TaskInteractionEntity interaction, TaskEventStatus status, String title) {
        TaskEventPayload payload = new TaskEventPayload();
        payload.setActionKey("interaction:user-input");
        payload.setActionInstanceId("interaction:" + interaction.getId());
        payload.setActionLabel(title);
        payload.setInteractionId(interaction.getId());
        payload.setInteractionStatus(interaction.getStatus().name());
        payload.setInteractionInputType(interaction.getInputType().name());
        payload.setQuestion(interaction.getQuestion());
        payload.setInteractionContent(interaction.getContent());
        payload.setOptions(interaction.getOptions());
        payload.setActions(interaction.getActions());
        payload.setFields(interaction.getFields());
        payload.setRequired(interaction.getRequired());
        payload.setPlaceholder(interaction.getPlaceholder());
        payload.setAnswerHint(interaction.getAnswerHint());
        payload.setDefaultValue(interaction.getDefaultValue());
        payload.setContextKey(interaction.getContextKey());
        payload.setAnswerText(interaction.getAnswerText());
        payload.setSelectedValues(interaction.getSelectedValues());
        payload.setAnswerValues(interaction.getAnswerValues());
        payload.setInteractionAnswerAction(interaction.getAnswerAction() == null ? null : interaction.getAnswerAction().name());
        payload.setInteractionAnswerActionKey(interaction.getAnswerActionKey());
        TaskExecutionEvent event = new TaskExecutionEvent();
        event.setType(TaskEventType.INTERACTION);
        event.setStatus(status);
        event.setTitle(title);
        event.setDetail(interactionEventDetail(interaction));
        event.setPayload(payload);
        var savedEvent = taskEventService.save(taskId, event);
        taskEventStreamService.publish(taskId, savedEvent);
    }

    private String interactionEventDetail(TaskInteractionEntity interaction) {
        if (interaction.getStatus() == TaskInteractionStatus.PENDING) {
            return interaction.getQuestion();
        }
        if (interaction.getStatus() == TaskInteractionStatus.ANSWERED) {
            String actionLabel = interactionActionLabel(interaction.getActions(), interaction.getAnswerActionKey());
            if (actionLabel != null) {
                return actionLabel;
            }
            if (interaction.getInputType() == TaskInteractionInputType.FORM) {
                return formAnswerDetail(interaction);
            }
            if (interaction.getSelectedValues() != null && !interaction.getSelectedValues().isEmpty()) {
                return interaction.getSelectedValues().stream()
                        .map(value -> interactionOptionLabel(interaction.getOptions(), value))
                        .collect(Collectors.joining("、"));
            }
            return TextKit.blankToNull(interaction.getAnswerText());
        }
        if (interaction.getStatus() == TaskInteractionStatus.SKIPPED) {
            return "已跳过";
        }
        if (interaction.getStatus() == TaskInteractionStatus.UNKNOWN) {
            return "不知道";
        }
        if (interaction.getStatus() == TaskInteractionStatus.CANCELED) {
            return "已取消";
        }
        if (interaction.getStatus() == TaskInteractionStatus.EXPIRED) {
            return "已超时";
        }
        return null;
    }

    private String interactionActionLabel(List<TaskInteractionAction> actions, String key) {
        if (actions == null || TextKit.blankToNull(key) == null) {
            return null;
        }
        return actions.stream()
                .filter(action -> action != null && key.equals(action.getKey()))
                .map(TaskInteractionAction::getLabel)
                .findFirst()
                .orElse(null);
    }

    private String formAnswerDetail(TaskInteractionEntity interaction) {
        if (interaction.getAnswerValues() == null || interaction.getAnswerValues().isEmpty()) {
            return null;
        }
        Map<String, TaskInteractionField> fields = (interaction.getFields() == null ? List.<TaskInteractionField>of() : interaction.getFields()).stream()
                .filter(field -> field != null && TextKit.blankToNull(field.getKey()) != null)
                .collect(Collectors.toMap(TaskInteractionField::getKey, field -> field, (left, right) -> left, LinkedHashMap::new));
        return interaction.getAnswerValues().stream()
                .map(answer -> formAnswerText(fields, answer))
                .filter(value -> TextKit.blankToNull(value) != null)
                .collect(Collectors.joining("；"));
    }

    private String formAnswerText(Map<String, TaskInteractionField> fields, TaskInteractionAnswerValue answer) {
        if (answer == null || TextKit.blankToNull(answer.getKey()) == null) {
            return null;
        }
        TaskInteractionField field = fields.get(answer.getKey());
        String label = field == null || TextKit.blankToNull(field.getLabel()) == null ? answer.getKey() : field.getLabel();
        String value = TextKit.blankToNull(answer.getValue());
        if (value == null && answer.getSelectedValues() != null && !answer.getSelectedValues().isEmpty()) {
            value = answer.getSelectedValues().stream()
                    .map(item -> interactionOptionLabel(field == null ? List.of() : field.getOptions(), item))
                    .collect(Collectors.joining("、"));
        }
        return value == null ? null : label + "：" + value;
    }

    private String interactionOptionLabel(List<TaskInteractionOption> options, String value) {
        if (options == null) {
            return value;
        }
        return options.stream()
                .filter(option -> option != null && value.equals(option.getValue()))
                .map(TaskInteractionOption::getLabel)
                .findFirst()
                .orElse(value);
    }

    private int normalizeTimeout(Integer timeoutSeconds) {
        int value = timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        return Math.min(Math.max(value, MIN_TIMEOUT_SECONDS), MAX_TIMEOUT_SECONDS);
    }

    private TaskInteractionResponse toResponse(TaskInteractionEntity entity) {
        TaskInteractionResponse response = new TaskInteractionResponse();
        response.setId(entity.getId());
        response.setTaskId(entity.getTask().getId());
        response.setQuestion(entity.getQuestion());
        response.setContent(entity.getContent());
        response.setInputType(entity.getInputType());
        response.setOptions(entity.getOptions() == null ? List.of() : entity.getOptions());
        response.setActions(entity.getActions() == null ? List.of() : entity.getActions());
        response.setFields(entity.getFields() == null ? List.of() : entity.getFields());
        response.setRequired(entity.getRequired());
        response.setPlaceholder(entity.getPlaceholder());
        response.setAnswerHint(entity.getAnswerHint());
        response.setDefaultValue(entity.getDefaultValue());
        response.setContextKey(entity.getContextKey());
        response.setStatus(entity.getStatus());
        response.setAnswerText(entity.getAnswerText());
        response.setSelectedValues(entity.getSelectedValues() == null ? List.of() : entity.getSelectedValues());
        response.setAnswerValues(entity.getAnswerValues() == null ? List.of() : entity.getAnswerValues());
        response.setAnswerAction(entity.getAnswerAction() == null ? null : entity.getAnswerAction().name());
        response.setAnswerActionKey(entity.getAnswerActionKey());
        response.setCreatedAt(entity.getCreatedAt());
        return response;
    }

    private String normalizeContextKey(String value) {
        String key = TextKit.blankToNull(value);
        if (key == null) {
            return null;
        }
        return key.trim();
    }

    private List<TaskInteractionField> normalizeFields(List<TaskInteractionField> fields) {
        if (fields == null) {
            return List.of();
        }
        return fields.stream()
                .filter(field -> field != null && TextKit.blankToNull(field.getKey()) != null && TextKit.blankToNull(field.getLabel()) != null)
                .map(field -> {
                    TaskInteractionField normalized = new TaskInteractionField();
                    normalized.setKey(field.getKey().trim());
                    normalized.setLabel(field.getLabel().trim());
                    normalized.setType(TaskInteractionInputType.parse(field.getType()).name());
                    normalized.setOptions(normalizeOptions(field.getOptions()));
                    normalized.setRequired(!Boolean.FALSE.equals(field.getRequired()));
                    normalized.setPlaceholder(TextKit.blankToNull(field.getPlaceholder()));
                    normalized.setDefaultValue(TextKit.blankToNull(field.getDefaultValue()));
                    normalized.setDescription(TextKit.blankToNull(field.getDescription()));
                    normalized.setContextKey(normalizeContextKey(field.getContextKey()));
                    return normalized;
                })
                .toList();
    }

    private List<TaskInteractionAnswerValue> normalizeAnswerValues(List<TaskInteractionAnswerValue> answerValues) {
        if (answerValues == null) {
            return List.of();
        }
        return answerValues.stream()
                .filter(answer -> answer != null && TextKit.blankToNull(answer.getKey()) != null)
                .map(answer -> {
                    TaskInteractionAnswerValue normalized = new TaskInteractionAnswerValue();
                    normalized.setKey(answer.getKey().trim());
                    normalized.setValue(TextKit.blankToNull(answer.getValue()));
                    normalized.setSelectedValues(answer.getSelectedValues() == null ? List.of() : answer.getSelectedValues().stream()
                            .filter(value -> TextKit.blankToNull(value) != null)
                            .map(String::trim)
                            .toList());
                    return normalized;
                })
                .toList();
    }

    private boolean hasMissingRequiredField(List<TaskInteractionField> fields, List<TaskInteractionAnswerValue> answerValues) {
        Map<String, TaskInteractionAnswerValue> answers = (answerValues == null ? List.<TaskInteractionAnswerValue>of() : answerValues).stream()
                .collect(Collectors.toMap(TaskInteractionAnswerValue::getKey, answer -> answer, (left, right) -> left, LinkedHashMap::new));
        return (fields == null ? List.<TaskInteractionField>of() : fields).stream()
                .filter(field -> !Boolean.FALSE.equals(field.getRequired()))
                .anyMatch(field -> {
                    TaskInteractionAnswerValue answer = answers.get(field.getKey());
                    return answer == null
                            || (TextKit.blankToNull(answer.getValue()) == null
                            && (answer.getSelectedValues() == null || answer.getSelectedValues().isEmpty()));
                });
    }
}
