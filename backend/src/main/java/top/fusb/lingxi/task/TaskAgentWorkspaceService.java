package top.fusb.lingxi.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.dto.TaskInputValue;
import top.fusb.lingxi.dto.TaskAttachmentResponse;
import top.fusb.lingxi.dto.TaskEventResponse;
import top.fusb.lingxi.dto.TaskEventPayload;
import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.entity.TaskRuntimeContextValueEntity;
import top.fusb.lingxi.kit.TextKit;
import top.fusb.lingxi.prompt.PromptTemplateService;
import top.fusb.lingxi.repository.AgentTaskRepository;
import top.fusb.lingxi.repository.TaskRuntimeContextValueRepository;
import top.fusb.lingxi.runtime.api.execution.RuntimeWorkspaceLayout;
import top.fusb.lingxi.resource.ResourceCatalogCandidate;
import top.fusb.lingxi.resource.ResourceCatalogCandidateFile;
import top.fusb.lingxi.resource.ResourceCatalogService;
import top.fusb.lingxi.definition.ModuleDefinition;
import top.fusb.lingxi.service.ModuleDefinitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskAgentWorkspaceService {

    public static final String TASK_WORKSPACE_DIR = ".agent-task";
    private static final int MAX_FILE_TEXT_LENGTH = 30_000;
    private static final int MAX_RECOVERY_EVENTS = 80;
    private static final int MAX_RECOVERY_EVENT_TEXT_LENGTH = 1_200;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AgentTaskRepository agentTaskRepository;
    private final ObjectMapper objectMapper;
    private final TaskRuntimeContextValueRepository contextValueRepository;
    private final ResourceCatalogService resourceCatalogService;
    private final TaskArtifactService taskArtifactService;
    private final TaskContentService taskContentService;
    private final TaskAttachmentService taskAttachmentService;
    private final TaskEventService taskEventService;
    private final ModuleDefinitionService moduleDefinitionService;
    private final PromptTemplateService promptTemplateService;

    /**
     * 初始化 agent 可读写的任务工作区。
     *
     * @param task 当前任务
     * @param workspaceRoot Agent 执行根目录
     * @param recovering 是否为中断后恢复同一次执行
     * @return `.agent-task` 目录路径
     */
    @Transactional(readOnly = true)
    public Path prepare(AgentTaskEntity task, Path workspaceRoot, boolean recovering) {
        Path taskDir = taskDir(workspaceRoot);
        try {
            AgentTaskEntity managedTask = managedTask(task);
            Files.createDirectories(taskDir.resolve("artifacts"));
            taskArtifactService.restoreForFollowUp(managedTask.getSourceTaskId(), taskDir.resolve("artifacts"));
            writeSourceTaskFile(managedTask, taskDir);
            writeTaskFile(managedTask, taskDir);
            writeAttachments(managedTask, taskDir);
            refreshContext(managedTask.getId(), workspaceRoot);
            writeResourceCandidatesFile(managedTask, taskDir);
            writeScenarioFile(managedTask, taskDir);
            writeCapabilitiesFile(managedTask, taskDir);
            writeRecoveryFile(managedTask, taskDir, recovering);
            writeArtifactsReadme(taskDir);
            return taskDir;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to prepare agent task workspace: " + e.getMessage(), e);
        }
    }

    /**
     * 刷新任务上下文文件，供 agent 在 compact/resume 后重新读取稳定事实。
     *
     * @param taskId 任务 ID
     * @param workspaceRoot Agent 执行根目录
     * @return 无返回值
     */
    @Transactional(readOnly = true)
    public void refreshContext(Long taskId, Path workspaceRoot) {
        if (taskId == null || workspaceRoot == null) {
            return;
        }
        AgentTaskEntity task = agentTaskRepository.findWithDetailsById(taskId)
                .orElseGet(() -> agentTaskRepository.findById(taskId).orElse(null));
        if (task == null) {
            return;
        }
        try {
            Path taskDir = taskDir(workspaceRoot);
            Files.createDirectories(taskDir);
            Files.writeString(taskDir.resolve("context.md"), contextText(task), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.info("刷新 agent 任务上下文失败 taskId={} message={}", taskId, e.getMessage());
        }
    }

    public Path taskDir(Path workspaceRoot) {
        return workspaceRoot.resolve(TASK_WORKSPACE_DIR).toAbsolutePath().normalize();
    }

    /**
     * 为 Backend 选定的 Runtime 创建并返回结构化工作区布局。
     *
     * @param workspaceRoot Agent 执行根目录
     * @param runtimeCode 当前 Runtime 编码
     * @return 所有路径均为绝对路径的 Runtime 工作区布局
     * @throws IllegalStateException 目录创建失败时抛出
     */
    public RuntimeWorkspaceLayout runtimeLayout(Path workspaceRoot, String runtimeCode) {
        if (workspaceRoot == null || runtimeCode == null || !runtimeCode.matches("[a-z0-9][a-z0-9-]{0,49}")) {
            throw new IllegalArgumentException("Runtime 工作区参数无效");
        }
        try {
            Path executionRoot = workspaceRoot.toAbsolutePath().normalize();
            Path taskContextRoot = taskDir(executionRoot);
            Path runtimeInputRoot = taskContextRoot.resolve("runtime-inputs").normalize();
            Path artifactsRoot = taskContextRoot.resolve("artifacts").normalize();
            Path runtimeRoot = taskContextRoot.resolve("runtime").normalize();
            Path runtimeStateRoot = runtimeRoot.resolve(runtimeCode).normalize();
            Path privateRuntimeRoot = runtimeRoot.resolve("private").normalize();
            Files.createDirectories(executionRoot);
            Files.createDirectories(taskContextRoot);
            Files.createDirectories(runtimeInputRoot);
            Files.createDirectories(artifactsRoot);
            Files.createDirectories(runtimeStateRoot);
            Files.createDirectories(privateRuntimeRoot);
            return new RuntimeWorkspaceLayout(
                    executionRoot.toString(), taskContextRoot.toString(), runtimeInputRoot.toString(),
                    artifactsRoot.toString(), runtimeRoot.toString(), runtimeStateRoot.toString(),
                    privateRuntimeRoot.toString());
        } catch (Exception exception) {
            throw new IllegalStateException("创建 Runtime 工作区布局失败：" + exception.getMessage(), exception);
        }
    }

    private void writeTaskFile(AgentTaskEntity task, Path taskDir) throws Exception {
        Files.writeString(taskDir.resolve("task.md"), taskText(task), StandardCharsets.UTF_8);
    }

    /**
     * 将中断前的持久化事件整理为恢复检查点，供 Runtime 继续执行。
     *
     * @param task 当前任务
     * @param taskDir `.agent-task` 目录
     * @param recovering 是否为中断后恢复同一次执行
     * @return 无返回值
     * @throws Exception 事件读取或检查点文件写入失败时抛出
     */
    private void writeRecoveryFile(AgentTaskEntity task, Path taskDir, boolean recovering) throws Exception {
        Path recoveryFile = taskDir.resolve("recovery.md");
        if (!recovering) {
            Files.deleteIfExists(recoveryFile);
            return;
        }
        List<TaskEventResponse> events = taskEventService.list(task.getId());
        int startIndex = Math.max(0, events.size() - MAX_RECOVERY_EVENTS);
        Map<String, TaskEventResponse> checkpoints = new LinkedHashMap<>();
        for (TaskEventResponse event : events.subList(startIndex, events.size())) {
            if (!"COMMAND".equals(event.getType())
                    && !"AGENT_MESSAGE".equals(event.getType())
                    && !"INTERACTION".equals(event.getType())
                    && !"ERROR".equals(event.getType())) {
                continue;
            }
            TaskEventPayload payload = event.getPayload();
            String instanceId = payload == null ? null : TextKit.blankToNull(payload.getActionInstanceId());
            String key = instanceId == null ? "event:" + event.getId() : "action:" + instanceId;
            checkpoints.put(key, event);
        }

        StringBuilder checkpointEvents = new StringBuilder();
        if (checkpoints.isEmpty()) {
            checkpointEvents.append(promptTemplateService.render("workspace-recovery-empty", Map.of()));
        }
        for (TaskEventResponse event : checkpoints.values()) {
            TaskEventPayload payload = event.getPayload();
            String label = payload == null ? null : TextKit.blankToNull(payload.getActionLabel());
            String target = payload == null ? null : TextKit.blankToNull(payload.getActionTarget());
            checkpointEvents.append("- 状态：").append(event.getStatus())
                    .append("；类型：").append(event.getType())
                    .append("；动作：").append(markdownText(label == null ? event.getTitle() : label));
            if (target != null) {
                checkpointEvents.append("；目标：`").append(markdownText(target)).append('`');
            }
            checkpointEvents.append('\n');
            String arguments = payload == null ? null : TextKit.blankToNull(payload.getArguments());
            String output = payload == null ? null : TextKit.blankToNull(payload.getOutput());
            String detail = TextKit.blankToNull(event.getDetail());
            if (arguments != null) {
                checkpointEvents.append("  - 参数：").append(markdownText(TextKit.limit(arguments, MAX_RECOVERY_EVENT_TEXT_LENGTH))).append('\n');
            }
            if (output != null) {
                checkpointEvents.append("  - 输出：").append(markdownText(TextKit.limit(output, MAX_RECOVERY_EVENT_TEXT_LENGTH))).append('\n');
            } else if (detail != null) {
                checkpointEvents.append("  - 详情：").append(markdownText(TextKit.limit(detail, MAX_RECOVERY_EVENT_TEXT_LENGTH))).append('\n');
            }
        }
        String content = promptTemplateService.render("workspace-recovery",
                Map.of("checkpointEvents", checkpointEvents.toString().strip()));
        Files.writeString(recoveryFile, TextKit.limit(content, MAX_FILE_TEXT_LENGTH), StandardCharsets.UTF_8);
    }

    /**
     * 将当前轮次及来源链中的用户附件复制到工作区并写入附件清单。
     *
     * @param task 当前任务
     * @param taskDir 当前任务的 `.agent-task` 目录
     * @return 无返回值
     * @throws Exception 附件读取、复制或清单写入失败时抛出
     */
    private void writeAttachments(AgentTaskEntity task, Path taskDir) throws Exception {
        List<TaskAttachmentResponse> attachments = inheritedAttachments(task);
        Path manifest = taskDir.resolve("attachments.md");
        if (attachments.isEmpty()) {
            Files.deleteIfExists(manifest);
            return;
        }
        Long ownerId = task.getOwner() == null ? null : task.getOwner().getId();
        List<String> filenames = taskAttachmentService.copyToWorkspace(attachments, ownerId, taskDir.resolve("attachments"));
        StringBuilder attachmentItems = new StringBuilder();
        for (int index = 0; index < attachments.size(); index++) {
            TaskAttachmentResponse attachment = attachments.get(index);
            attachmentItems.append("- `.agent-task/attachments/")
                    .append(filenames.get(index))
                    .append("` - ")
                    .append(markdownText(attachment.getName()))
                    .append("；类型：")
                    .append(attachment.getContentType())
                    .append("；大小：")
                    .append(attachment.getSize())
                    .append(" 字节");
            if ("user-input".equals(attachment.getInputKind())) {
                attachmentItems.append("; ").append(promptTemplateService.text("workspace.fullUserInputMarker"));
            }
            attachmentItems.append('\n');
        }
        Files.writeString(manifest, promptTemplateService.render("workspace-attachments",
                Map.of("attachmentItems", attachmentItems.toString().strip())), StandardCharsets.UTF_8);
    }

    private List<TaskAttachmentResponse> inheritedAttachments(AgentTaskEntity task) {
        Map<String, TaskAttachmentResponse> result = new LinkedHashMap<>();
        Set<Long> visitedTaskIds = new LinkedHashSet<>();
        AgentTaskEntity current = task;
        while (current != null && current.getId() != null && visitedTaskIds.add(current.getId())) {
            if (current.getAttachments() != null) {
                current.getAttachments().forEach(attachment -> result.putIfAbsent(attachment.getId(), attachment));
            }
            current = current.getSourceTaskId() == null
                    ? null
                    : agentTaskRepository.findWithDetailsById(current.getSourceTaskId()).orElse(null);
        }
        return new ArrayList<>(result.values());
    }

    private String markdownText(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("`", "\\`").replace("\n", " ").replace("\r", " ");
    }

    /**
     * 将来源任务问题和完整结果写入当前工作区，供续问恢复或派生场景按需读取。
     *
     * @param task 当前任务
     * @param taskDir 当前任务的 `.agent-task` 目录
     * @return 无返回值
     * @throws Exception 来源结果读取或文件写入失败时抛出
     */
    private void writeSourceTaskFile(AgentTaskEntity task, Path taskDir) throws Exception {
        if (task.getSourceTaskId() == null) {
            Files.deleteIfExists(taskDir.resolve("source-task.md"));
            return;
        }
        AgentTaskEntity source = agentTaskRepository.findWithDetailsById(task.getSourceTaskId()).orElse(null);
        if (source == null) {
            Files.writeString(taskDir.resolve("source-task.md"),
                    promptTemplateService.render("workspace-source-task-missing",
                            Map.of("taskId", task.getSourceTaskId().toString())), StandardCharsets.UTF_8);
            return;
        }
        String result = taskContentService.read(source.getId(), "result", source.getResultText());
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("taskId", source.getId().toString());
        variables.put("scenario", nullToEmpty(source.getScenario()));
        variables.put("title", nullToEmpty(source.getTitle()));
        variables.put("userInput", nullToEmpty(source.getUserInput()));
        variables.put("result", nullToEmpty(result));
        String content = promptTemplateService.render("workspace-source-task", variables);
        Files.writeString(taskDir.resolve("source-task.md"), content, StandardCharsets.UTF_8);
    }

    private AgentTaskEntity managedTask(AgentTaskEntity task) {
        if (task == null || task.getId() == null) {
            return task;
        }
        return agentTaskRepository.findWithDetailsById(task.getId()).orElse(task);
    }

    private void writeScenarioFile(AgentTaskEntity task, Path taskDir) throws Exception {
        Files.writeString(taskDir.resolve("scenario.md"), scenarioText(task), StandardCharsets.UTF_8);
    }

    private void writeCapabilitiesFile(AgentTaskEntity task, Path taskDir) throws Exception {
        Files.writeString(taskDir.resolve("capabilities.md"), capabilitiesText(task), StandardCharsets.UTF_8);
    }

    /**
     * 根据任务问题和分析情境召回已挂载能力过去发现的资源，并写入 Agent 工作区。
     *
     * @param task 当前任务
     * @param taskDir 当前任务的 `.agent-task` 目录
     * @return 无返回值
     * @throws Exception 候选文件写入失败时抛出
     */
    private void writeResourceCandidatesFile(AgentTaskEntity task, Path taskDir) throws Exception {
        Path resourceFile = taskDir.resolve("resources.json");
        List<ResourceCatalogCandidate> candidates = resourceCatalogService.search(task.getId(), resourceQueryText(task));
        if (candidates.isEmpty()) {
            Files.deleteIfExists(resourceFile);
            Files.deleteIfExists(taskDir.resolve("resources.md"));
            return;
        }
        ResourceCatalogCandidateFile content = new ResourceCatalogCandidateFile(
                promptTemplateService.text("workspace.resourceNotice"),
                candidates);
        Files.writeString(resourceFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(content), StandardCharsets.UTF_8);
    }

    /**
     * 汇总当前问题、来源问题、任务参数和分析情境，作为通用资源目录检索文本。
     *
     * @param task 当前任务
     * @return 不包含能力专用字段判断的检索文本
     */
    private String resourceQueryText(AgentTaskEntity task) {
        StringBuilder builder = new StringBuilder();
        builder.append(nullToEmpty(task.getTitle())).append('\n');
        builder.append(nullToEmpty(task.getUserInput())).append('\n');
        if (task.getSourceTaskId() != null) {
            agentTaskRepository.findById(task.getSourceTaskId()).ifPresent(source -> builder
                    .append(nullToEmpty(source.getTitle())).append('\n')
                    .append(nullToEmpty(source.getUserInput())).append('\n'));
        }
        for (TaskInputValue value : task.getInputValues() == null ? List.<TaskInputValue>of() : task.getInputValues()) {
            builder.append(nullToEmpty(value.getValue())).append('\n');
        }
        if (task.getPremiseSnapshotContextValues() != null) {
            task.getPremiseSnapshotContextValues().values().forEach(value -> builder.append(nullToEmpty(value)).append('\n'));
        }
        contextValueRepository.findByTaskIdOrderByContextKeyAsc(task.getId())
                .forEach(value -> builder.append(nullToEmpty(value.getContextValue())).append('\n'));
        return builder.toString();
    }

    private void writeArtifactsReadme(Path taskDir) throws Exception {
        Files.writeString(taskDir.resolve("artifacts").resolve("README.md"),
                promptTemplateService.render("workspace-artifacts", Map.of()), StandardCharsets.UTF_8);
    }

    private String taskText(AgentTaskEntity task) {
        StringBuilder metadata = new StringBuilder();
        metadata.append("- 任务ID：").append(task.getId()).append('\n');
        metadata.append("- 会话根任务ID：").append(rootId(task)).append('\n');
        metadata.append("- 轮次：").append(task.getRoundNo() == null ? 1 : task.getRoundNo()).append('\n');
        if (task.getSourceTaskId() != null) {
            metadata.append("- 来源任务ID：").append(task.getSourceTaskId()).append('\n');
        }
        metadata.append("- 场景：").append(nullToEmpty(task.getScenario())).append('\n');
        metadata.append("- 状态：").append(task.getStatus()).append('\n');
        metadata.append("- 标题：").append(nullToEmpty(task.getTitle())).append('\n');
        if (task.getCreatedAt() != null) {
            metadata.append("- 创建时间：").append(TIME_FORMATTER.format(task.getCreatedAt())).append('\n');
        }
        StringBuilder conversationRounds = new StringBuilder();
        appendRounds(conversationRounds, task);
        String content = promptTemplateService.render("workspace-task", Map.of(
                "taskMetadata", metadata.toString().strip(),
                "userInput", nullToEmpty(task.getUserInput()),
                "conversationRounds", conversationRounds.toString().strip()));
        return TextKit.limit(content, MAX_FILE_TEXT_LENGTH);
    }

    private String contextText(AgentTaskEntity task) {
        StringBuilder inputValues = new StringBuilder();
        StringBuilder configuredContext = new StringBuilder();
        StringBuilder runtimeContext = new StringBuilder();
        appendInputValues(inputValues, task.getInputValues());
        appendPremise(configuredContext, task);
        appendRuntimeContext(runtimeContext, task.getId());
        String guidance = TextKit.blankToNull(task.getPremiseSnapshotPromptText());
        String content = promptTemplateService.render("workspace-context-document", Map.of(
                "inputValues", inputValues.toString().strip(),
                "configuredContext", configuredContext.toString().strip(),
                "runtimeContext", runtimeContext.toString().strip(),
                "configuredContextGuidance", guidance == null ? promptTemplateService.text("workspace.none") : guidance.trim()));
        return TextKit.limit(content, MAX_FILE_TEXT_LENGTH);
    }

    private String scenarioText(AgentTaskEntity task) {
        StringBuilder metadata = new StringBuilder();
        String emptyText = promptTemplateService.text("workspace.none");
        metadata.append("- 编码：").append(nullToEmpty(task.getScenarioCode())).append('\n');
        metadata.append("- 名称：").append(nullToEmpty(task.getScenarioName())).append('\n');
        metadata.append("- 场景：").append(nullToEmpty(task.getScenario()));
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("scenarioMetadata", metadata.toString().strip());
        variables.put("parameterDefinitions", emptyText);
        variables.put("workflow", nullToEmpty(task.getRuntimeInstructions()));
        variables.put("additionalGuides", "");
        variables.put("followUpCandidates", "");
        return TextKit.limit(promptTemplateService.render("workspace-scenario", variables), MAX_FILE_TEXT_LENGTH);
    }

    private String capabilitiesText(AgentTaskEntity task) {
        Set<String> capabilityCodes = agentTaskRepository.findTaskCapabilityCodesByTaskId(task.getId());
        StringBuilder enabledModules = new StringBuilder();
        for (ModuleDefinition module : moduleDefinitionService.listCapabilities()) {
            if (module.getCode() == null || !capabilityCodes.contains(module.getCode())) {
                continue;
            }
            enabledModules.append("- ").append(nullToEmpty(module.getName())).append(" (`")
                    .append(module.getCode()).append("`)");
            if (TextKit.blankToNull(module.getDescription()) != null) {
                enabledModules.append(": ").append(module.getDescription().trim());
            }
            enabledModules.append("。\n");
        }
        String resourceMemory = promptTemplateService.render("workspace-resource-memory",
                Map.of("providerCodes", String.join(", ", capabilityCodes)));
        String content = promptTemplateService.render("workspace-capabilities", Map.of(
                "enabledModules", enabledModules.isEmpty() ? promptTemplateService.text("workspace.none") : enabledModules.toString().strip(),
                "resourceMemory", resourceMemory));
        return TextKit.limit(content, MAX_FILE_TEXT_LENGTH);
    }

    private void appendRounds(StringBuilder builder, AgentTaskEntity task) {
        Long rootId = rootId(task);
        Map<Long, AgentTaskEntity> rounds = new LinkedHashMap<>();
        agentTaskRepository.findWithDetailsById(rootId).ifPresent(root -> rounds.put(root.getId(), root));
        for (AgentTaskEntity round : agentTaskRepository.findByConversationRootTaskIdOrderByRoundNoAscCreatedAtAsc(rootId)) {
            rounds.put(round.getId(), round);
        }
        if (rounds.size() <= 1) {
            return;
        }
        StringBuilder roundItems = new StringBuilder();
        for (AgentTaskEntity round : rounds.values()) {
            roundItems.append("- 第 ").append(round.getRoundNo() == null ? 1 : round.getRoundNo()).append(" 轮")
                    .append("；任务ID=").append(round.getId())
                    .append("；状态=").append(round.getStatus())
                    .append("；输入=").append(oneLine(round.getUserInput()))
                    .append('\n');
        }
        builder.append(promptTemplateService.render("workspace-conversation-rounds",
                Map.of("roundItems", roundItems.toString().strip())));
    }

    private void appendInputValues(StringBuilder builder, List<TaskInputValue> values) {
        if (values == null || values.isEmpty()) {
            builder.append(promptTemplateService.text("workspace.none"));
            return;
        }
        for (TaskInputValue value : values) {
            if (value != null && TextKit.blankToNull(value.getKey()) != null) {
                builder.append("- ").append(value.getKey()).append(": ").append(nullToEmpty(value.getValue())).append('\n');
            }
        }
    }

    private void appendPremise(StringBuilder builder, AgentTaskEntity task) {
        if (TextKit.blankToNull(task.getPremiseSnapshotName()) == null
                && (task.getPremiseSnapshotContextValues() == null || task.getPremiseSnapshotContextValues().isEmpty())) {
            builder.append(promptTemplateService.text("workspace.none"));
            return;
        }
        if (TextKit.blankToNull(task.getPremiseSnapshotName()) != null) {
            builder.append("- 名称：").append(task.getPremiseSnapshotName()).append('\n');
        }
        if (TextKit.blankToNull(task.getPremiseSnapshotDescription()) != null) {
            builder.append("- 描述：").append(task.getPremiseSnapshotDescription()).append('\n');
        }
        if (task.getPremiseSnapshotContextValues() != null) {
            for (Map.Entry<String, String> entry : task.getPremiseSnapshotContextValues().entrySet()) {
                builder.append("- ").append(entry.getKey()).append(": ").append(nullToEmpty(entry.getValue())).append('\n');
            }
        }
    }

    private void appendRuntimeContext(StringBuilder builder, Long taskId) {
        List<TaskRuntimeContextValueEntity> values = contextValueRepository.findByTaskIdOrderByContextKeyAsc(taskId);
        if (values.isEmpty()) {
            builder.append(promptTemplateService.text("workspace.none"));
            return;
        }
        for (TaskRuntimeContextValueEntity value : values) {
            builder.append("- ").append(value.getContextKey()).append(": ").append(nullToEmpty(value.getContextValue())).append('\n');
        }
    }

    private Long rootId(AgentTaskEntity task) {
        return task.getConversationRootTaskId() == null ? task.getId() : task.getConversationRootTaskId();
    }

    private String oneLine(String value) {
        String text = TextKit.blankToNull(value);
        return text == null ? "" : TextKit.limit(text.replaceAll("\\s+", " ").trim(), 360);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
