package top.fusb.lingxi.runtime.codex.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.runtime.api.execution.RuntimeCommandDescriptor;
import top.fusb.lingxi.runtime.api.execution.RuntimeCommandGuideDescriptor;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionEnvironment;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionRequest;
import top.fusb.lingxi.runtime.api.execution.RuntimeSkillDescriptor;
import top.fusb.lingxi.runtime.api.event.RuntimeActionIcon;
import top.fusb.lingxi.runtime.api.event.RuntimeEvent;
import top.fusb.lingxi.runtime.api.event.RuntimeEventStatus;
import top.fusb.lingxi.runtime.api.event.RuntimeEventType;
import top.fusb.lingxi.runtime.api.model.RuntimeModelConfig;
import top.fusb.lingxi.runtime.codex.cli.CodexEventParser;
import top.fusb.lingxi.runtime.codex.config.CodexRuntimeProperties;
import top.fusb.lingxi.runtime.codex.home.CodexHomeService;
import top.fusb.lingxi.runtime.codex.maintenance.CodexCliManager;
import top.fusb.lingxi.runtime.codex.session.CodexSessionEventStream;
import top.fusb.lingxi.runtime.codex.usage.CodexUsageSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodexExecutorCommandActivityTest {

    @Test
    void exposesPublicCommandsAndGuidePathsWithoutPrivateEntrypoints() {
        ObjectMapper objectMapper = new ObjectMapper();
        CodexHomeService homeService = mock(CodexHomeService.class);
        when(homeService.commandGuideLocator("project-hub"))
                .thenReturn("$CODEX_HOME/command-catalog/project-hub/guide.md");
        when(homeService.commandGuideLocator("resource-memory"))
                .thenReturn("$CODEX_HOME/command-catalog/resource-memory/guide.md");
        CodexExecutor executor = new CodexExecutor(
                new CodexRuntimeProperties(), mock(CodexCliManager.class), homeService,
                mock(CodexUsageSessionService.class), objectMapper, new CodexEventParser(objectMapper),
                new CodexSessionEventStream(objectMapper));
        RuntimeCommandDescriptor command = new RuntimeCommandDescriptor(
                "project-hub", "project-hub.project-context", "project-hub project-context",
                "读取项目上下文", null, "/private/bin/project-hub", List.of("project-context"), List.of());
        RuntimeCommandDescriptor resourceCommand = new RuntimeCommandDescriptor(
                null, "resource-memory.search", "resource-memory search", "检索资源记忆",
                "检索可复用资源", "/private/bin/resource-memory", List.of("search"), List.of(),
                "resource-memory", List.of());
        RuntimeExecutionEnvironment environment = new RuntimeExecutionEnvironment(
                Map.of(), List.of("/private/bin"), List.of(), List.of(), List.of(command, resourceCommand),
                List.of(new RuntimeSkillDescriptor("project-hub", "项目管理上下文", "/private/skill")),
                List.of(new RuntimeCommandGuideDescriptor("resource-memory", "资源记忆", "private content")));

        String instructions = executor.commandCatalogInstructions(environment);

        assertThat(instructions)
                .contains("公开业务命令已注入当前任务 PATH", "项目管理上下文 (`project-hub`)",
                        "$CODEX_HOME/command-catalog/project-hub/guide.md",
                        "资源记忆 (`resource-memory`)",
                        "$CODEX_HOME/command-catalog/resource-memory/guide.md",
                        "`project-hub project-context`：读取项目上下文")
                .doesNotContain("/private/bin", "/private/skill", "private content", ".py", "SKILL.md");

        String platformInstructions = executor.platformCommandInstructions(environment);
        assertThat(platformInstructions)
                .contains("资源记忆 (`resource-memory`)",
                        "$CODEX_HOME/command-catalog/resource-memory/guide.md",
                        "`resource-memory search`：检索资源记忆")
                .doesNotContain("project-hub project-context", "/private/bin", "/private/skill");
    }

    @Test
    void ignoresMcpToolCallSummariesFromCliStdout() {
        ObjectMapper objectMapper = new ObjectMapper();
        CodexExecutor executor = new CodexExecutor(
                new CodexRuntimeProperties(), mock(CodexCliManager.class), mock(CodexHomeService.class),
                mock(CodexUsageSessionService.class), objectMapper, new CodexEventParser(objectMapper),
                new CodexSessionEventStream(objectMapper));

        Object started = ReflectionTestUtils.invokeMethod(executor, "parseJsonEvent", """
                {"type":"item.started","item":{"id":"item_0","type":"mcp_tool_call","server":"codex","tool":"list_mcp_resources","arguments":{},"result":null,"status":"in_progress"}}
                """);
        Object completed = ReflectionTestUtils.invokeMethod(executor, "parseJsonEvent", """
                {"type":"item.completed","item":{"id":"item_0","type":"mcp_tool_call","server":"codex","tool":"list_mcp_resources","arguments":{},"result":{"content":[]},"error":null,"status":"completed"}}
                """);

        assertThat(started).isNull();
        assertThat(completed).isNull();
    }

    @Test
    void combinesStructuredMcpInstructionsInsideCodexRuntime() {
        CodexExecutor executor = executor();
        RuntimeExecutionRequest request = mock(RuntimeExecutionRequest.class);
        when(request.instructions()).thenReturn("稳定场景契约");
        when(request.mcpInstructions()).thenReturn("# MCP 使用说明\n\n服务说明");

        String instructions = ReflectionTestUtils.invokeMethod(executor, "runtimeInstructions", request);

        assertThat(instructions).isEqualTo("稳定场景契约\n\n# MCP 使用说明\n\n服务说明");
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvesPublicSkillCommandByItsFullCommandSignature() {
        ObjectMapper objectMapper = new ObjectMapper();
        CodexExecutor executor = new CodexExecutor(
                new CodexRuntimeProperties(), mock(CodexCliManager.class), mock(CodexHomeService.class),
                mock(CodexUsageSessionService.class), objectMapper, new CodexEventParser(objectMapper),
                new CodexSessionEventStream(objectMapper));
        Map<String, RuntimeCommandDescriptor> definitions =
                (Map<String, RuntimeCommandDescriptor>) ReflectionTestUtils.getField(executor, "commandDefinitions");
        assertThat(definitions).isNotNull();
        definitions.put("project-hub project-context",
                new RuntimeCommandDescriptor(
                        "project-hub", "project-hub.project-context",
                        "project-hub project-context",
                        "读取项目上下文", null, "/private/bin/project-hub",
                        List.of("project-context"), List.of(), null, List.of(), RuntimeActionIcon.BOOK_OPEN));

        Object activity = ReflectionTestUtils.invokeMethod(executor, "commandActivity",
                "project-hub project-context --project-id app:1", "item-8");

        assertThat(ReflectionTestUtils.getField(activity, "key"))
                .isEqualTo("capability:project-hub:project-context");
        assertThat(ReflectionTestUtils.getField(activity, "label")).isEqualTo("读取项目上下文");
        assertThat(ReflectionTestUtils.getField(activity, "icon")).isEqualTo(RuntimeActionIcon.BOOK_OPEN);
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvesThePublicCommandInsideACompoundShellCommand() {
        ObjectMapper objectMapper = new ObjectMapper();
        CodexExecutor executor = new CodexExecutor(
                new CodexRuntimeProperties(), mock(CodexCliManager.class), mock(CodexHomeService.class),
                mock(CodexUsageSessionService.class), objectMapper, new CodexEventParser(objectMapper),
                new CodexSessionEventStream(objectMapper));
        Map<String, RuntimeCommandDescriptor> definitions =
                (Map<String, RuntimeCommandDescriptor>) ReflectionTestUtils.getField(executor, "commandDefinitions");
        definitions.put("project-hub project-context", new RuntimeCommandDescriptor(
                "project-hub", "project-hub.project-context", "project-hub project-context",
                "读取项目上下文", null, "/private/bin/project-hub",
                List.of("project-context"), List.of()));

        Object activity = ReflectionTestUtils.invokeMethod(executor, "commandActivity",
                "cd /tmp/work && project-hub project-context --project-id app:1", "item-9");

        assertThat(ReflectionTestUtils.getField(activity, "key"))
                .isEqualTo("capability:project-hub:project-context");
        assertThat(ReflectionTestUtils.getField(activity, "target"))
                .isEqualTo("project-hub project-context --project-id app:1");
    }

    @Test
    void presentsShellVariableWorkingDirectoryAsCommandContext() {
        CodexExecutor executor = executor();

        Object activity = ReflectionTestUtils.invokeMethod(executor, "commandActivity",
                "WT=/data/git/checkouts/sample-service/revision; sed -n '1,80p' \"$WT/src/App.java\"", "item-10");

        assertThat(ReflectionTestUtils.getField(activity, "key")).isEqualTo("command:sed");
        assertThat(ReflectionTestUtils.getField(activity, "target"))
                .isEqualTo("sed -n '1,80p' \"src/App.java\"");
    }

    @Test
    void presentsChangeDirectoryPrefixAsCommandContext() {
        CodexExecutor executor = executor();

        Object activity = ReflectionTestUtils.invokeMethod(executor, "commandActivity",
                "cd /data/git/checkouts/sample-service/revision && rg -n 'Agreement' .", "item-11");

        assertThat(ReflectionTestUtils.getField(activity, "key")).isEqualTo("command:rg");
        assertThat(ReflectionTestUtils.getField(activity, "target"))
                .isEqualTo("rg -n 'Agreement' .");
        assertThat(ReflectionTestUtils.getField(activity, "icon")).isEqualTo(RuntimeActionIcon.TERMINAL);
    }

    @Test
    void includesAnIconWheneverCodexAddsAnEventLabel() {
        CodexExecutor executor = executor();

        RuntimeEvent event = ReflectionTestUtils.invokeMethod(executor, "event",
                RuntimeEventType.THINKING, RuntimeEventStatus.RUNNING, "codex.reasoning", null, null);

        assertThat(event).isNotNull();
        assertThat(event.payload().actionLabel()).isEqualTo("正在思考");
        assertThat(event.payload().actionIcon()).isEqualTo(RuntimeActionIcon.HEAD_CIRCUIT);
    }

    @Test
    void formatsStructuredProviderErrorsForEventDisplay() throws Exception {
        CodexExecutor executor = executor();
        String providerError = """
                {"error":{"message":"Service is too busy.","type":"service_unavailable_error","param":null,"code":"service_unavailable_error"}}
                """;
        String eventLine = new ObjectMapper().writeValueAsString(Map.of(
                "type", "error",
                "message", providerError
        ));

        RuntimeEvent event = ReflectionTestUtils.invokeMethod(executor, "parseJsonEvent", eventLine);

        String readableError = """
                错误：Service is too busy.
                类型：service_unavailable_error
                错误码：service_unavailable_error
                参数：无""";
        assertThat(event).isNotNull();
        assertThat(event.detail()).isEqualTo(readableError);
        assertThat(event.payload().message()).isEqualTo(readableError);
    }

    @Test
    void appliesResolvedLocaleToCodexProcessEnvironment() {
        ObjectMapper objectMapper = new ObjectMapper();
        CodexHomeService homeService = mock(CodexHomeService.class);
        when(homeService.processLocale()).thenReturn("zh_CN.utf8");
        CodexExecutor executor = new CodexExecutor(
                new CodexRuntimeProperties(), mock(CodexCliManager.class), homeService,
                mock(CodexUsageSessionService.class), objectMapper, new CodexEventParser(objectMapper),
                new CodexSessionEventStream(objectMapper));
        ProcessBuilder builder = new ProcessBuilder("codex");
        RuntimeModelConfig modelConfig = new RuntimeModelConfig(
                "test-key", "SYSTEM", "https://example.test/v1", "test-model",
                "high", 200_000, true);

        ReflectionTestUtils.invokeMethod(executor, "applyRuntimeEnvironment",
                builder, modelConfig, Path.of("/tmp/codex-home"));

        assertThat(builder.environment())
                .containsEntry("LANG", "zh_CN.utf8")
                .containsEntry("LC_ALL", "zh_CN.utf8")
                .containsEntry("LC_CTYPE", "zh_CN.utf8");
    }

    private CodexExecutor executor() {
        ObjectMapper objectMapper = new ObjectMapper();
        return new CodexExecutor(
                new CodexRuntimeProperties(), mock(CodexCliManager.class), mock(CodexHomeService.class),
                mock(CodexUsageSessionService.class), objectMapper, new CodexEventParser(objectMapper),
                new CodexSessionEventStream(objectMapper));
    }
}
