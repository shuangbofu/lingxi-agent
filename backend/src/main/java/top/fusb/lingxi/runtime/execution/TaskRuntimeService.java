package top.fusb.lingxi.runtime.execution;

import top.fusb.lingxi.dto.TaskExecutionEvent;
import top.fusb.lingxi.dto.TokenUsageSnapshot;
import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.enums.ApiKeySource;
import top.fusb.lingxi.runtime.config.ModelCatalogService;
import top.fusb.lingxi.runtime.config.RuntimeModeService;
import top.fusb.lingxi.runtime.config.RuntimeModelProfileConfig;
import top.fusb.lingxi.runtime.mcp.McpServerService;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.kit.TextKit;
import top.fusb.lingxi.repository.AgentTaskRepository;
import top.fusb.lingxi.runtime.api.capability.RuntimeCapabilityAccess;
import top.fusb.lingxi.runtime.api.event.RuntimeEventListener;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionRequest;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionResult;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionEnvironment;
import top.fusb.lingxi.runtime.api.execution.RuntimeWorkspaceLayout;
import top.fusb.lingxi.runtime.api.model.RuntimeModelConfig;
import top.fusb.lingxi.runtime.api.model.RuntimeMessageDelta;
import top.fusb.lingxi.runtime.api.model.RuntimeSessionRef;
import top.fusb.lingxi.runtime.api.model.RuntimeUsage;
import top.fusb.lingxi.runtime.api.model.RuntimeUsageQuery;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpServerConfig;
import top.fusb.lingxi.runtime.core.AgentRuntimeService;
import top.fusb.lingxi.runtime.capability.CapabilityRuntimeScriptService;
import top.fusb.lingxi.task.TaskAgentWorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskRuntimeService {

    private final AgentRuntimeService agentRuntimeService;
    private final AgentTaskRepository agentTaskRepository;
    private final TransactionTemplate transactionTemplate;
    private final ModelCatalogService modelCatalogService;
    private final RuntimeModeService runtimeModeService;
    private final CapabilityRuntimeScriptService capabilityRuntimeScriptService;
    private final McpServerService mcpServerService;
    private final TaskAgentWorkspaceService taskAgentWorkspaceService;

    /**
     * 将平台任务上下文转换为通用运行时请求并执行。
     *
     * @param task 当前任务
     * @param workspacePath 任务工作目录
     * @param capabilityCodes 允许使用的能力编码
     * @param capabilityCommands 能力允许使用的命令；null 表示不限制命令
     * @param recovering 是否为中断后恢复同一次执行
     * @param timeoutSeconds 执行超时秒数
     * @param eventConsumer 任务事件回调
     * @param messageDeltaConsumer Agent 消息增量回调
     * @param usageConsumer Token 用量回调
     * @return 统一运行时执行结果
     */
    public RuntimeExecutionResult execute(AgentTaskEntity task,
                                          String workspacePath,
                                          Set<String> capabilityCodes,
                                          Map<String, Set<String>> capabilityCommands,
                                          boolean recovering,
                                          long timeoutSeconds,
                                          Consumer<TaskExecutionEvent> eventConsumer,
                                          Consumer<RuntimeMessageDelta> messageDeltaConsumer,
                                          Consumer<TokenUsageSnapshot> usageConsumer) {
        String runtimeCode = runtimeCode(task);
        RuntimeSessionRef resumeSession = resumeSession(task, runtimeCode, recovering);
        log.info("准备运行时执行 taskId={} runtimeCode={} recovering={} capabilityCount={} resumeSession={}",
                task.getId(), runtimeCode, recovering, capabilityCodes == null ? 0 : capabilityCodes.size(),
                resumeSession != null);
        try {
            RuntimeModelConfig selectedModel = modelConfig(selectedTaskModel(task));
            RuntimeWorkspaceLayout workspace = taskAgentWorkspaceService.runtimeLayout(
                    java.nio.file.Path.of(workspacePath), runtimeCode);
            RuntimeExecutionEnvironment environment = capabilityRuntimeScriptService.ensureScript(
                    task.getId(), workspace, task.getOwner() == null ? null : task.getOwner().getUsername(),
                    capabilityCodes, capabilityCommands);
            Set<String> availableFeatures = environment.commands().stream()
                    .flatMap(command -> command.outputFeatures().stream())
                    .collect(java.util.stream.Collectors.toSet());
            List<RuntimeMcpServerConfig> mcpServers = mcpServerService.enabledForRuntime(
                    runtimeCode, availableFeatures);
            String taskInstructions = taskInstructions(task);
            String mcpInstructions = mcpInstructions(mcpServers);
            RuntimeExecutionRequest request = request(task, workspace,
                    taskInstructions, mcpInstructions,
                    recoveryPrompt(task, recovering), selectedModel, capabilityCodes, capabilityCommands,
                    mcpServers, environment.withSensitiveValue(selectedModel.apiKey()),
                    resumeSession, recovering, timeoutSeconds);
            return agentRuntimeService.execute(runtimeCode, request,
                    listener(eventConsumer, messageDeltaConsumer, usageConsumer));
        } finally {
            capabilityRuntimeScriptService.revokeAccess(task.getId());
        }
    }

    public boolean cancel(Long taskId) {
        AgentTaskEntity task = agentTaskRepository.findById(taskId).orElse(null);
        String runtimeCode = task == null ? runtimeModeService.defaultCode() : runtimeCode(task);
        return agentRuntimeService.cancel(runtimeCode, taskId.toString());
    }

    public Optional<TokenUsageSnapshot> readUsage(AgentTaskEntity task) {
        RuntimeUsageQuery query = new RuntimeUsageQuery(
                task.getId().toString(),
                task.getConversationRootTaskId() == null ? null : task.getConversationRootTaskId().toString(),
                task.getStartedAt(),
                task.getEndedAt() == null ? java.time.LocalDateTime.now() : task.getEndedAt()
        );
        return agentRuntimeService.readUsage(runtimeCode(task), query).map(TaskRuntimeMapper::toTokenUsage);
    }

    private RuntimeSessionRef resumeSession(AgentTaskEntity task, String runtimeCode, boolean recovering) {
        if (recovering) {
            String currentSessionId = TextKit.blankToNull(task.getRuntimeSessionId());
            if (currentSessionId != null) {
                return new RuntimeSessionRef(currentSessionId, TextKit.blankToNull(task.getRuntimeSessionPath()));
            }
            Long conversationId = task.getConversationRootTaskId() == null ? task.getId() : task.getConversationRootTaskId();
            return agentRuntimeService.latestSession(runtimeCode, conversationId.toString())
                    .map(session -> saveRecoveredSession(task.getId(), session))
                    .orElse(null);
        }
        if (task.getSourceTaskId() == null || task.getRoundNo() == null || task.getRoundNo() <= 1) {
            return null;
        }
        AgentTaskEntity source = agentTaskRepository.findWithDetailsById(task.getSourceTaskId()).orElse(null);
        if (source == null) {
            return null;
        }
        String sourceRuntimeCode = agentRuntimeService.requireCode(source.getRuntimeCode());
        if (!runtimeCode.equals(sourceRuntimeCode)) {
            return null;
        }
        String sourceSessionId = TextKit.blankToNull(source.getRuntimeSessionId());
        if (sourceSessionId != null) {
            String sourceSessionPath = TextKit.blankToNull(source.getRuntimeSessionPath());
            if (sourceSessionPath != null) {
                return new RuntimeSessionRef(sourceSessionId, sourceSessionPath);
            }
            Long rootId = task.getConversationRootTaskId() == null ? source.getId() : task.getConversationRootTaskId();
            return agentRuntimeService.latestSession(runtimeCode, rootId.toString())
                    .filter(session -> sourceSessionId.equals(session.sessionId()))
                    .map(session -> saveRecoveredSession(source.getId(), session))
                    .orElseGet(() -> new RuntimeSessionRef(sourceSessionId, null));
        }
        Long rootId = task.getConversationRootTaskId() == null ? source.getId() : task.getConversationRootTaskId();
        return agentRuntimeService.latestSession(runtimeCode, rootId.toString())
                .map(session -> saveRecoveredSession(source.getId(), session))
                .orElse(null);
    }

    private String recoveryPrompt(AgentTaskEntity task, boolean recovering) {
        if (!recovering) {
            return taskUserMessage(task);
        }
        String instruction = """
                # 同一次执行恢复

                这是同一次执行在中断后的继续，不是新请求。
                先读取 `.agent-task/task.md`、`.agent-task/context.md`、`.agent-task/scenario.md`、`.agent-task/capabilities.md` 和 `.agent-task/recovery.md`，原始任务目标以 `task.md` 为准。
                根据恢复检查点和现有产物从第一个未完成步骤继续；检查点为空时从原始任务的第一个步骤开始，不要要求用户重新提供任务目标。
                成功事件的输出文件仍然存在时，必须复用，不得重复执行已完成的工具或能力命令。
                只有检查确认必要输出缺失或损坏时，才重新执行对应的单个步骤。
                """;
        return instruction;
    }

    private String runtimeCode(AgentTaskEntity task) {
        return agentRuntimeService.requireCode(task.getRuntimeCode());
    }

    private RuntimeEventListener listener(Consumer<TaskExecutionEvent> eventConsumer,
                                          Consumer<RuntimeMessageDelta> messageDeltaConsumer,
                                          Consumer<TokenUsageSnapshot> usageConsumer) {
        return new RuntimeEventListener() {
            @Override
            public void onEvent(top.fusb.lingxi.runtime.api.event.RuntimeEvent event) {
                eventConsumer.accept(TaskRuntimeMapper.toTaskEvent(event));
            }

            @Override
            public void onMessageDelta(RuntimeMessageDelta delta) {
                messageDeltaConsumer.accept(delta);
            }

            @Override
            public void onUsage(RuntimeUsage usage) {
                usageConsumer.accept(TaskRuntimeMapper.toTokenUsage(usage));
            }
        };
    }

    private RuntimeExecutionRequest request(AgentTaskEntity task,
                                            RuntimeWorkspaceLayout workspace,
                                            String taskInstructions,
                                            String mcpInstructions,
                                            String prompt,
                                            RuntimeModelConfig modelConfig,
                                            Set<String> capabilityCodes,
                                            Map<String, Set<String>> capabilityCommands,
                                            List<RuntimeMcpServerConfig> mcpServers,
                                            RuntimeExecutionEnvironment environment,
                                            RuntimeSessionRef resumeSession,
                                            boolean recovering,
                                            long timeoutSeconds) {
        return new RuntimeExecutionRequest(
                task.getId().toString(),
                task.getConversationRootTaskId() == null ? null : task.getConversationRootTaskId().toString(),
                task.getId().toString(),
                workspace,
                taskInstructions,
                taskInstructions,
                mcpInstructions,
                prompt,
                taskUserMessage(task),
                task.getFinalResponseInstructions(),
                true,
                true,
                task.getOwner() == null ? null : task.getOwner().getUsername(),
                modelConfig,
                capabilityCodes.stream()
                        .sorted(Comparator.naturalOrder())
                        .map(code -> new RuntimeCapabilityAccess(code,
                                capabilityCommands == null ? Set.of() : capabilityCommands.getOrDefault(code, Set.of())))
                        .toList(),
                capabilityCommands != null,
                mcpServers,
                environment,
                resumeSession,
                recovering,
                timeoutSeconds
        );
    }

    /**
     * 提取当前任务实际挂载的 MCP 使用说明。
     *
     * @param mcpServers 当前任务实际挂载的 MCP 配置
     * @return 当前任务实际挂载的 MCP 使用说明
     */
    private String mcpInstructions(List<RuntimeMcpServerConfig> mcpServers) {
        StringBuilder result = new StringBuilder();
        boolean headingAdded = false;
        for (RuntimeMcpServerConfig server : mcpServers) {
            String instructions = TextKit.blankToNull(server.instructions());
            if (instructions == null) {
                continue;
            }
            if (!headingAdded) {
                result.append("# MCP 使用说明\n");
                headingAdded = true;
            }
            result.append("\n## ").append(server.name()).append(" (`")
                    .append(server.code()).append("`)\n\n")
                    .append(instructions.trim()).append('\n');
        }
        return result.toString();
    }

    private RuntimeModelConfig modelConfig(RuntimeModelProfileConfig profile) {
        String apiKey = TextKit.blankToNull(profile.getApiKey());
        return new RuntimeModelConfig(
                apiKey,
                apiKey == null ? ApiKeySource.NONE.name() : ApiKeySource.SYSTEM.name(),
                profile.getBaseUrl(),
                profile.getModel(),
                profile.getReasoningEffort(),
                profile.getContextWindowTokens(),
                profile.getProtocol(),
                profile.getInstructionPrompt(),
                profile.isImageInputSupported(),
                profile.getThinkingFieldName(),
                profile.getProviderType()
        );
    }

    private RuntimeModelProfileConfig selectedTaskModel(AgentTaskEntity task) {
        RuntimeModelProfileConfig current = modelCatalogService.visibleModel(task.getOwner(), task.getModelProfileId())
                .orElseThrow(() -> new BizException(ErrorCode.TASK_ERROR, ErrorSubCode.RUNTIME_UNAVAILABLE,
                        "任务使用的模型配置已删除或不可用"));
        RuntimeModelProfileConfig profile = new RuntimeModelProfileConfig();
        profile.setId(task.getModelProfileId());
        profile.setName(TextKit.blankToNull(task.getModelName()) == null
                ? current.getName()
                : task.getModelName());
        profile.setModel(TextKit.blankToNull(task.getModelIdentifier()) == null
                ? current.getModel()
                : task.getModelIdentifier());
        // 连接地址、密钥和供应商协议元数据必须来自同一份当前配置。
        profile.setBaseUrl(current.getBaseUrl());
        profile.setApiKey(current.getApiKey());
        profile.setProviderType(current.getProviderType());
        profile.setThinkingFieldName(current.getThinkingFieldName());
        profile.setProtocol(task.getModelProtocol() == null
                ? current.getProtocol()
                : task.getModelProtocol());
        profile.setContextWindowTokens(task.getModelContextWindowTokens() == null
                ? current.getContextWindowTokens()
                : task.getModelContextWindowTokens());
        profile.setReasoningEffort(TextKit.blankToNull(task.getModelReasoningEffort()) == null
                ? current.getReasoningEffort()
                : task.getModelReasoningEffort());
        profile.setInstructionPrompt(task.getModelInstructionPrompt() == null
                ? current.getInstructionPrompt()
                : task.getModelInstructionPrompt());
        profile.setImageInputSupported(task.getModelImageInputSupported() == null
                ? current.isImageInputSupported()
                : task.getModelImageInputSupported());
        profile.setEnabled(true);
        return profile;
    }

    private String taskInstructions(AgentTaskEntity task) {
        String instructions = TextKit.blankToNull(task.getRuntimeInstructions());
        return instructions == null ? "" : instructions;
    }

    private String taskUserMessage(AgentTaskEntity task) {
        String userMessage = TextKit.blankToNull(task.getRuntimeUserMessage());
        return userMessage == null ? task.getPrompt() : userMessage;
    }

    private RuntimeSessionRef saveRecoveredSession(Long sourceTaskId, RuntimeSessionRef session) {
        transactionTemplate.executeWithoutResult(status -> {
            AgentTaskEntity source = agentTaskRepository.findWithDetailsById(sourceTaskId)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.TASK_NOT_FOUND));
            boolean changed = false;
            if (TextKit.blankToNull(source.getRuntimeSessionId()) == null) {
                source.setRuntimeSessionId(session.sessionId());
                changed = true;
            }
            if (TextKit.blankToNull(source.getRuntimeSessionPath()) == null
                    && TextKit.blankToNull(session.sessionPath()) != null) {
                source.setRuntimeSessionPath(session.sessionPath());
                changed = true;
            }
            if (changed) {
                agentTaskRepository.save(source);
                log.info("补写历史运行时 session sourceTaskId={} sessionId={}", sourceTaskId, session.sessionId());
            }
        });
        return session;
    }
}
