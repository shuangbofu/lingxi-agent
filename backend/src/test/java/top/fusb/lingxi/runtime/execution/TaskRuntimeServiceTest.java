package top.fusb.lingxi.runtime.execution;

import top.fusb.lingxi.dto.TaskExecutionEvent;
import top.fusb.lingxi.dto.TokenUsageSnapshot;
import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.repository.AgentTaskRepository;
import top.fusb.lingxi.runtime.api.event.RuntimeEvent;
import top.fusb.lingxi.runtime.api.event.RuntimeEventListener;
import top.fusb.lingxi.runtime.api.event.RuntimeEventPayload;
import top.fusb.lingxi.runtime.api.event.RuntimeEventStatus;
import top.fusb.lingxi.runtime.api.event.RuntimeEventType;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionEnvironment;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionRequest;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionResult;
import top.fusb.lingxi.runtime.api.execution.RuntimeWorkspaceLayout;
import top.fusb.lingxi.runtime.api.model.RuntimeMessageDelta;
import top.fusb.lingxi.runtime.api.model.RuntimeModelProtocol;
import top.fusb.lingxi.runtime.api.model.RuntimeSessionRef;
import top.fusb.lingxi.runtime.api.model.RuntimeUsage;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpServerConfig;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpTransport;
import top.fusb.lingxi.runtime.capability.CapabilityRuntimeScriptService;
import top.fusb.lingxi.runtime.config.ModelCatalogService;
import top.fusb.lingxi.runtime.config.RuntimeModeService;
import top.fusb.lingxi.runtime.config.RuntimeModelProfileConfig;
import top.fusb.lingxi.runtime.core.AgentRuntimeService;
import top.fusb.lingxi.runtime.mcp.McpServerService;
import top.fusb.lingxi.task.TaskAgentWorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskRuntimeServiceTest {

    private final AgentRuntimeService agentRuntimeService = mock(AgentRuntimeService.class);
    private final AgentTaskRepository taskRepository = mock(AgentTaskRepository.class);
    private final ModelCatalogService modelCatalogService = mock(ModelCatalogService.class);
    private final RuntimeModeService runtimeModeService = mock(RuntimeModeService.class);
    private final CapabilityRuntimeScriptService capabilityRuntimeScriptService = mock(CapabilityRuntimeScriptService.class);
    private final McpServerService mcpServerService = mock(McpServerService.class);
    private final TaskAgentWorkspaceService taskAgentWorkspaceService = mock(TaskAgentWorkspaceService.class);
    private TaskRuntimeService service;

    @BeforeEach
    void setUp() {
        when(modelCatalogService.visibleModel(nullable(UserEntity.class), nullable(String.class)))
                .thenReturn(Optional.of(modelProfile("analysis-model")));
        when(capabilityRuntimeScriptService.ensureScript(any(), any(), any(), any(), any()))
                .thenReturn(RuntimeExecutionEnvironment.empty());
        when(mcpServerService.enabledForRuntime(any(), any())).thenReturn(List.of());
        when(taskAgentWorkspaceService.runtimeLayout(any(), any())).thenAnswer(invocation ->
                layout(invocation.getArgument(0), invocation.getArgument(1)));
        service = new TaskRuntimeService(agentRuntimeService, taskRepository, mock(TransactionTemplate.class),
                modelCatalogService, runtimeModeService, capabilityRuntimeScriptService, mcpServerService,
                taskAgentWorkspaceService);
    }

    @Test
    void executesTaskOnceWithSelectedModelAndCompleteCapabilitySet() {
        when(agentRuntimeService.requireCode("langchain")).thenReturn("langchain");
        when(agentRuntimeService.execute(eq("langchain"), any(RuntimeExecutionRequest.class), any(RuntimeEventListener.class)))
                .thenReturn(new RuntimeExecutionResult(0, "done", "", "done", null, null, LocalDateTime.now()));
        AgentTaskEntity task = task(31L, "langchain", "分析订单异常");
        task.setRuntimeInstructions("稳定场景契约");
        task.setRuntimeUserMessage("分析订单异常");

        service.execute(task, "/tmp/task-31", Set.of("project-hub", "code-repository"),
                Map.of("project-hub", Set.of("project-context")), false, 300L,
                event -> { }, delta -> { }, usage -> { });

        ArgumentCaptor<RuntimeExecutionRequest> captor = ArgumentCaptor.forClass(RuntimeExecutionRequest.class);
        verify(agentRuntimeService).execute(eq("langchain"), captor.capture(), any(RuntimeEventListener.class));
        RuntimeExecutionRequest request = captor.getValue();
        assertThat(request.eventNamespace()).isEqualTo("31");
        assertThat(request.modelConfig().model()).isEqualTo("analysis-model");
        assertThat(request.instructions()).isEqualTo("稳定场景契约");
        assertThat(request.prompt()).isEqualTo("分析订单异常");
        assertThat(request.finalResponseRequired()).isTrue();
        assertThat(request.workspaceFileToolsEnabled()).isTrue();
        assertThat(request.capabilities()).extracting("code")
                .containsExactly("code-repository", "project-hub");
    }

    @Test
    void resumesPreviousSessionAndMapsRuntimeCallbacks() {
        when(agentRuntimeService.requireCode("langchain")).thenReturn("langchain");
        AgentTaskEntity source = task(40L, "langchain", "上一轮");
        source.setRuntimeSessionId("session-40");
        source.setRuntimeSessionPath("/tmp/session-40");
        when(taskRepository.findWithDetailsById(40L)).thenReturn(Optional.of(source));
        RuntimeUsage usage = new RuntimeUsage("now", 2L, 100L, 20L, 0L, 30L, 5L, 130L,
                60L, 10L, 0L, 15L, 3L, 75L, 128000L);
        RuntimeEvent event = new RuntimeEvent(RuntimeEventType.COMMAND, RuntimeEventStatus.SUCCESS,
                "capability:project-hub", "done",
                new RuntimeEventPayload("item.completed", "command_execution", "call-1", "completed", null,
                        null, "call-1", null, "{}", null, 0, "project-hub", "call-1", "读取项目", null, false));
        when(agentRuntimeService.execute(eq("langchain"), any(RuntimeExecutionRequest.class), any(RuntimeEventListener.class)))
                .thenAnswer(invocation -> {
                    RuntimeEventListener listener = invocation.getArgument(2);
                    listener.onEvent(event);
                    listener.onMessageDelta(new RuntimeMessageDelta("message-1", "partial"));
                    listener.onUsage(usage);
                    return new RuntimeExecutionResult(0, "done", "", "done", null, usage, LocalDateTime.now());
                });
        AgentTaskEntity task = task(41L, "langchain", "继续分析");
        task.setSourceTaskId(40L);
        task.setConversationRootTaskId(40L);
        task.setRoundNo(2);
        AtomicReference<TaskExecutionEvent> mappedEvent = new AtomicReference<>();
        AtomicReference<RuntimeMessageDelta> mappedDelta = new AtomicReference<>();
        AtomicReference<TokenUsageSnapshot> mappedUsage = new AtomicReference<>();

        service.execute(task, "/tmp/task-41", Set.of(), null, false, 300L,
                mappedEvent::set, mappedDelta::set, mappedUsage::set);

        ArgumentCaptor<RuntimeExecutionRequest> captor = ArgumentCaptor.forClass(RuntimeExecutionRequest.class);
        verify(agentRuntimeService).execute(eq("langchain"), captor.capture(), any(RuntimeEventListener.class));
        assertThat(captor.getValue().resumeSession()).isEqualTo(new RuntimeSessionRef("session-40", "/tmp/session-40"));
        assertThat(mappedEvent.get().getPayload().getActionLabel()).isEqualTo("读取项目");
        assertThat(mappedDelta.get().delta()).isEqualTo("partial");
        assertThat(mappedUsage.get().getTotalTokens()).isEqualTo(130L);
    }

    @Test
    void recoveryUsesDurableWorkspaceInstructions() {
        when(agentRuntimeService.requireCode("codex")).thenReturn("codex");
        when(agentRuntimeService.latestSession("codex", "19"))
                .thenReturn(Optional.of(new RuntimeSessionRef("session-19", "/tmp/session-19")));
        when(agentRuntimeService.execute(eq("codex"), any(RuntimeExecutionRequest.class), any(RuntimeEventListener.class)))
                .thenReturn(new RuntimeExecutionResult(0, "done", "", "done", null, null, LocalDateTime.now()));
        AgentTaskEntity task = task(19L, "codex", "original prompt");

        service.execute(task, "/tmp/task-19", Set.of(), null, true, 300L,
                event -> { }, delta -> { }, usage -> { });

        ArgumentCaptor<RuntimeExecutionRequest> captor = ArgumentCaptor.forClass(RuntimeExecutionRequest.class);
        verify(agentRuntimeService).execute(eq("codex"), captor.capture(), any(RuntimeEventListener.class));
        assertThat(captor.getValue().resumeSession()).isEqualTo(new RuntimeSessionRef("session-19", "/tmp/session-19"));
        assertThat(captor.getValue().prompt())
                .contains("同一次执行恢复", ".agent-task/recovery.md", ".agent-task/task.md")
                .doesNotContain("original prompt");
    }

    @Test
    void keepsMcpInstructionsStructuredForCodexRuntime() {
        when(agentRuntimeService.requireCode("codex")).thenReturn("codex");
        when(agentRuntimeService.execute(eq("codex"), any(RuntimeExecutionRequest.class), any(RuntimeEventListener.class)))
                .thenReturn(new RuntimeExecutionResult(0, "done", "", "done", null, null, LocalDateTime.now()));
        RuntimeExecutionEnvironment environment = new RuntimeExecutionEnvironment(
                Map.of(), List.of(), List.of(), List.of(),
                List.of(new top.fusb.lingxi.runtime.api.execution.RuntimeCommandDescriptor(
                        "code-repository", "code-repo.prepare", "code-repo prepare",
                        "准备仓库", null, "/private/code-repo", List.of("prepare"),
                        List.of(new top.fusb.lingxi.runtime.api.execution.RuntimeCommandOutputDescriptor(
                                "file-root", "worktreePath", Set.of("file-access", "code-index"))))),
                List.of());
        when(capabilityRuntimeScriptService.ensureScript(any(), any(), any(), any(), any()))
                .thenReturn(environment);
        RuntimeMcpServerConfig codebase = new RuntimeMcpServerConfig(
                "codebase-memory", "Codebase Memory", "先调用 list_projects，未命中时调用 index_repository。",
                RuntimeMcpTransport.STDIO, "./.tools/codebase-memory-mcp", List.of(), null,
                Map.of(), Map.of(), Set.of("code-index"), Set.of("list_projects", "index_repository"), Map.of());
        when(mcpServerService.enabledForRuntime("codex", Set.of("file-access", "code-index")))
                .thenReturn(List.of(codebase));
        AgentTaskEntity task = task(52L, "codex", "分析代码");
        task.setRuntimeInstructions("稳定场景契约");

        service.execute(task, "/tmp/task-52", Set.of("code-repository"), null, false, 300L,
                event -> { }, delta -> { }, usage -> { });

        ArgumentCaptor<RuntimeExecutionRequest> captor = ArgumentCaptor.forClass(RuntimeExecutionRequest.class);
        verify(agentRuntimeService).execute(eq("codex"), captor.capture(), any(RuntimeEventListener.class));
        RuntimeExecutionRequest request = captor.getValue();
        assertThat(request.mcpServers()).containsExactly(codebase);
        assertThat(request.instructions()).isEqualTo("稳定场景契约");
        assertThat(request.mcpInstructions()).contains(
                "# MCP 使用说明", "Codebase Memory (`codebase-memory`)",
                "先调用 list_projects，未命中时调用 index_repository。");
    }

    @Test
    void keepsMcpCatalogOutOfLangChainInitialInstructions() {
        when(agentRuntimeService.requireCode("langchain")).thenReturn("langchain");
        when(agentRuntimeService.execute(eq("langchain"), any(RuntimeExecutionRequest.class), any(RuntimeEventListener.class)))
                .thenReturn(new RuntimeExecutionResult(0, "done", "", "done", null, null, LocalDateTime.now()));
        RuntimeMcpServerConfig codebase = new RuntimeMcpServerConfig(
                "codebase-memory", "Codebase Memory", "完整 MCP 指令只应按需读取。",
                RuntimeMcpTransport.STDIO, "codebase-memory-mcp", List.of(), null,
                Map.of(), Map.of(), Set.of(), Set.of("list_projects", "search_code"), Map.of());
        when(mcpServerService.enabledForRuntime("langchain", Set.of())).thenReturn(List.of(codebase));
        AgentTaskEntity task = task(53L, "langchain", "分析代码");
        task.setRuntimeInstructions("稳定场景契约");

        service.execute(task, "/tmp/task-53", Set.of(), null, false, 300L,
                event -> { }, delta -> { }, usage -> { });

        ArgumentCaptor<RuntimeExecutionRequest> captor = ArgumentCaptor.forClass(RuntimeExecutionRequest.class);
        verify(agentRuntimeService).execute(eq("langchain"), captor.capture(), any(RuntimeEventListener.class));
        RuntimeExecutionRequest request = captor.getValue();
        assertThat(request.mcpServers()).containsExactly(codebase);
        assertThat(request.instructions())
                .contains("稳定场景契约")
                .doesNotContain("# MCP 按需目录", "Codebase Memory", "codebase-memory",
                        "list_projects", "search_code", "read_mcp_tool", "invoke_mcp_tool",
                        "完整 MCP 指令只应按需读取。");
        assertThat(request.mcpInstructions()).contains(
                "# MCP 使用说明", "Codebase Memory (`codebase-memory`)", "完整 MCP 指令只应按需读取。");
    }

    private AgentTaskEntity task(Long id, String runtimeCode, String prompt) {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(id);
        task.setConversationRootTaskId(id);
        task.setRoundNo(1);
        task.setRuntimeCode(runtimeCode);
        task.setPrompt(prompt);
        task.setModelProfileId("analysis-model");
        return task;
    }

    private RuntimeWorkspaceLayout layout(Path executionRoot, String runtimeCode) {
        Path root = executionRoot.toAbsolutePath().normalize();
        Path taskContext = root.resolve("backend-context");
        Path runtimeRoot = taskContext.resolve("runtime-data");
        return new RuntimeWorkspaceLayout(
                root.toString(), taskContext.toString(), taskContext.resolve("inputs").toString(),
                taskContext.resolve("artifacts").toString(), runtimeRoot.toString(),
                runtimeRoot.resolve(runtimeCode).toString(), runtimeRoot.resolve("private").toString());
    }

    private RuntimeModelProfileConfig modelProfile(String model) {
        RuntimeModelProfileConfig profile = new RuntimeModelProfileConfig();
        profile.setId(model);
        profile.setName(model);
        profile.setModel(model);
        profile.setBaseUrl("https://example.test/v1");
        profile.setApiKey("system-key");
        profile.setProtocol(RuntimeModelProtocol.RESPONSES);
        profile.setContextWindowTokens(128_000);
        profile.setEnabled(true);
        return profile;
    }
}
