package top.fusb.lingxi.runtime.langchain.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.runtime.api.execution.RuntimeCommandDescriptor;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionEnvironment;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionRequest;
import top.fusb.lingxi.runtime.api.execution.RuntimeSkillDescriptor;
import top.fusb.lingxi.runtime.api.execution.RuntimeWorkspaceLayout;
import top.fusb.lingxi.runtime.api.event.RuntimeActionIcon;
import top.fusb.lingxi.runtime.api.event.RuntimeEvent;
import top.fusb.lingxi.runtime.api.event.RuntimeEventStatus;
import top.fusb.lingxi.runtime.api.model.RuntimeModelConfig;
import top.fusb.lingxi.runtime.api.model.RuntimeModelProtocol;
import top.fusb.lingxi.runtime.langchain.agent.activity.LangChainActivityEventCoordinator;
import top.fusb.lingxi.runtime.langchain.agent.memory.LangChainCompactionStateStore;
import top.fusb.lingxi.runtime.langchain.agent.memory.LangChainFileChatMemoryStore;
import top.fusb.lingxi.runtime.langchain.capability.LangChainCapabilityRegistry;
import top.fusb.lingxi.runtime.langchain.config.LangChainRuntimeProperties;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultLangChainRuntimeDelegateTest {

    @TempDir
    Path workspace;

    @Test
    void keepsFinalizationGraceOutsideExecutionTimeout() {
        DefaultLangChainRuntimeDelegate delegate = new DefaultLangChainRuntimeDelegate(new LangChainRuntimeProperties());
        try {
            long deadline = TimeUnit.SECONDS.toNanos(900L);

            assertThat(delegate.finalizationDeadlineNanos(deadline, 120L))
                    .isEqualTo(TimeUnit.SECONDS.toNanos(1_020L));
        } finally {
            delegate.close();
        }
    }

    @Test
    void preservesProgressWhenExecutionReachesTimeLimit() {
        DefaultLangChainRuntimeDelegate delegate = new DefaultLangChainRuntimeDelegate(new LangChainRuntimeProperties());
        try {
            assertThat(delegate.timeoutAnswer("已查询 879 个资产包", "其中 32 个待复核"))
                    .contains("达到执行时间上限", "已查询 879 个资产包", "其中 32 个待复核")
                    .doesNotContain("执行失败");
            assertThat(delegate.finalizationFallback("已形成的调查结论", "未完成的润色文本"))
                    .isEqualTo("已形成的调查结论");
            assertThat(delegate.markTimeLimitedResult("已形成的调查结论"))
                    .contains("达到执行时间上限", "阶段性结果", "已形成的调查结论");
        } finally {
            delegate.close();
        }
    }

    @Test
    void returnsFinalizedPartialResultAfterExplorationTimeout() throws Exception {
        Path executionRoot = Files.createDirectories(workspace.resolve("execution"));
        Path taskContextRoot = Files.createDirectories(workspace.resolve("task-context"));
        Path runtimeInputRoot = Files.createDirectories(workspace.resolve("runtime-input"));
        Path artifactsRoot = Files.createDirectories(workspace.resolve("artifacts"));
        Path runtimeRoot = Files.createDirectories(workspace.resolve("runtime"));
        Path runtimeStateRoot = Files.createDirectories(workspace.resolve("runtime-state"));
        Path privateRuntimeRoot = Files.createDirectories(workspace.resolve("private-runtime"));
        AtomicInteger modelCalls = new AtomicInteger();
        LangChainRuntimeProperties properties = new LangChainRuntimeProperties();
        properties.setTimeFinalizationGraceSeconds(2L);
        DefaultLangChainRuntimeDelegate delegate = new DefaultLangChainRuntimeDelegate(properties) {
            @Override
            StreamingChatModel streamingModel(RuntimeModelConfig config, long timeoutSeconds,
                                               LangChainActivityEventCoordinator activityCoordinator) {
                return new StreamingChatModel() {
                    @Override
                    public void doChat(dev.langchain4j.model.chat.request.ChatRequest request,
                                       StreamingChatResponseHandler handler) {
                        if (modelCalls.incrementAndGet() == 1) {
                            return;
                        }
                        handler.onCompleteResponse(ChatResponse.builder()
                                .aiMessage(AiMessage.from("已根据现有调查信息整理出阶段性结论"))
                                .build());
                    }
                };
            }
        };
        RuntimeModelConfig modelConfig = new RuntimeModelConfig(
                "test-key", "SYSTEM", "https://example.test/v1", "test-model",
                null, 128_000, RuntimeModelProtocol.RESPONSES, false);
        RuntimeExecutionRequest request = new RuntimeExecutionRequest(
                "timeout-execution", "timeout-conversation", "timeout-execution",
                new RuntimeWorkspaceLayout(
                        executionRoot.toString(), taskContextRoot.toString(), runtimeInputRoot.toString(),
                        artifactsRoot.toString(), runtimeRoot.toString(), runtimeStateRoot.toString(),
                        privateRuntimeRoot.toString()),
                "", "", "", "调查并给出结论", "调查并给出结论", "", true, true,
                "test-user", modelConfig, List.of(), false, List.of(), RuntimeExecutionEnvironment.empty(),
                null, false, 1L);
        try {
            var result = delegate.execute(request, event -> { });

            assertThat(result.exitCode()).isZero();
            assertThat(result.resultText())
                    .contains("达到执行时间上限", "阶段性结果", "已根据现有调查信息整理出阶段性结论");
            assertThat(result.stdoutText()).contains("达到执行时间上限", result.resultText());
            assertThat(result.session()).isNotNull();
            assertThat(Path.of(result.session().sessionPath())).exists();
            assertThat(modelCalls).hasValue(2);
        } finally {
            delegate.close();
        }
    }

    @Test
    void systemPromptRequiresVisibleProgressBeforeFurtherToolCalls() throws Exception {
        String prompt;
        try (var input = DefaultLangChainRuntimeDelegate.class
                .getResourceAsStream("/prompts/langchain-agent-system.md")) {
            assertThat(input).isNotNull();
            prompt = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(prompt)
                .contains("凡是还要继续调用工具")
                .contains("不得只返回空文本的工具调用")
                .contains("用户可见进展必须写在普通正文中")
                .contains("证据足够后立即回答")
                .doesNotContain("run_capability_command");
    }

    @Test
    void appendsModelInstructionToSystemPrompt() {
        DefaultLangChainRuntimeDelegate delegate = new DefaultLangChainRuntimeDelegate(new LangChainRuntimeProperties());
        RuntimeModelConfig modelConfig = new RuntimeModelConfig(
                "test-key", "SYSTEM", "https://api.deepseek.com/v1", "deepseek-v4",
                "medium", 128_000, RuntimeModelProtocol.CHAT_COMPLETIONS,
                "章节之间只使用标题和空行分隔", false);
        try {
            assertThat(delegate.systemPrompt("业务化输出，不展示实现标识", modelConfig))
                    .contains("业务化输出，不展示实现标识",
                            "# 当前模型附加约束", "章节之间只使用标题和空行分隔", "以原有契约为准");
        } finally {
            delegate.close();
        }
    }

    @Test
    void finalDeliveryKeepsOriginalTaskAuthoritativeAndDisablesFurtherInvestigation() {
        DefaultLangChainRuntimeDelegate delegate = new DefaultLangChainRuntimeDelegate(new LangChainRuntimeProperties());
        RuntimeModelConfig modelConfig = new RuntimeModelConfig(
                "test-key", "SYSTEM", "https://api.deepseek.com/v1", "deepseek-v4",
                "medium", 128_000, RuntimeModelProtocol.CHAT_COMPLETIONS,
                "不要展示实现标识", false);
        try {
            assertThat(delegate.finalizationSystemPrompt("按业务语言交付", modelConfig,
                    "仅在最终答案按需输出 lingxi-view"))
                    .contains("按业务语言交付", "不要展示实现标识", "# 最终交付阶段")
                    .contains("原始任务输入是任务目标的唯一权威来源", "调查工具已经关闭")
                    .contains("仅在最终答案按需输出 lingxi-view");
            assertThat(delegate.finalizationPrompt("只说明业务影响", "扫描全部类名并输出代码清单"))
                    .contains("<original-task>\n只说明业务影响\n</original-task>")
                    .contains("<investigation-draft>\n扫描全部类名并输出代码清单\n</investigation-draft>");
        } finally {
            delegate.close();
        }
    }

    @Test
    void finalDeliveryRejectsToolCallsAndUsesCompletedText() {
        DefaultLangChainRuntimeDelegate delegate = new DefaultLangChainRuntimeDelegate(new LangChainRuntimeProperties());
        try {
            ChatResponse completed = ChatResponse.builder()
                    .aiMessage(AiMessage.from("业务化结果"))
                    .build();
            assertThat(delegate.requireFinalDelivery("execution-final", completed, "partial"))
                    .isEqualTo("业务化结果");

            ToolExecutionRequest toolCall = ToolExecutionRequest.builder()
                    .id("call-1")
                    .name("read_file")
                    .arguments("{}")
                    .build();
            ChatResponse invalid = ChatResponse.builder()
                    .aiMessage(AiMessage.from(toolCall))
                    .build();
            assertThatThrownBy(() -> delegate.requireFinalDelivery("execution-final", invalid, ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("不允许的工具调用");
        } finally {
            delegate.close();
        }
    }

    @Test
    void restoresPreviousConversationIntoCurrentTaskMemory() {
        Path previousFile = workspace.resolve("task-85/chat-memory.json");
        Path currentFile = workspace.resolve("task-86/chat-memory.json");
        LangChainFileChatMemoryStore previous = new LangChainFileChatMemoryStore(previousFile);
        previous.updateMessages("85", List.of(
                UserMessage.from("找一个可以请款的资产包"),
                AiMessage.from("已按未申请状态查询到 879 个资产包")
        ));
        LangChainCompactionStateStore previousState = new LangChainCompactionStateStore(
                LangChainCompactionStateStore.stateFileForMemory(previousFile), new ObjectMapper());
        previousState.write(new LangChainCompactionStateStore.State(1, "上一轮已确认查询口径"));
        LangChainFileChatMemoryStore current = new LangChainFileChatMemoryStore(currentFile);
        LangChainCompactionStateStore currentState = new LangChainCompactionStateStore(
                LangChainCompactionStateStore.stateFileForMemory(currentFile), new ObjectMapper());

        int restored = current.restoreFrom(previousFile);
        boolean stateRestored = currentState.restoreFrom(
                LangChainCompactionStateStore.stateFileForMemory(previousFile));

        assertThat(restored).isEqualTo(2);
        assertThat(stateRestored).isTrue();
        assertThat(currentState.read()).isEqualTo(
                new LangChainCompactionStateStore.State(1, "上一轮已确认查询口径"));
        assertThat(current.getMessages("86")).containsExactly(
                UserMessage.from("找一个可以请款的资产包"),
                AiMessage.from("已按未申请状态查询到 879 个资产包")
        );
    }

    @Test
    void recoversLegacySkillAccessOnlyFromSuccessfulCommandHistory() {
        DefaultLangChainRuntimeDelegate delegate = new DefaultLangChainRuntimeDelegate(new LangChainRuntimeProperties());
        ToolExecutionRequest successful = ToolExecutionRequest.builder()
                .id("call-project")
                .name("run_skill_command")
                .arguments("{\"command\":\"project-hub project-list\",\"arguments\":[]}")
                .build();
        ToolExecutionRequest failed = ToolExecutionRequest.builder()
                .id("call-code")
                .name("run_skill_command")
                .arguments("{\"command\":\"code-repo prepare\",\"arguments\":[]}")
                .build();
        List<RuntimeCommandDescriptor> commands = List.of(
                new RuntimeCommandDescriptor("project-hub", "project-hub.list", "project-hub project-list",
                        "项目列表", "", "/tmp/project-hub", List.of(), List.of()),
                new RuntimeCommandDescriptor("code-repository", "code-repository.prepare", "code-repo prepare",
                        "准备仓库", "", "/tmp/code-repo", List.of(), List.of()));
        List<ChatMessage> messages = List.of(
                AiMessage.from(successful),
                ToolExecutionResultMessage.from(successful, "项目列表"),
                AiMessage.from(failed),
                ToolExecutionResultMessage.builder()
                        .id(failed.id())
                        .toolName(failed.name())
                        .text("工具执行失败")
                        .isError(true)
                        .build());
        try {
            assertThat(delegate.recoverSkillAccessFromHistory(commands, messages))
                    .containsExactly("project-hub");
        } finally {
            delegate.close();
        }
    }

    @Test
    void selectsClientByConfiguredModelProtocol() {
        DefaultLangChainRuntimeDelegate delegate = new DefaultLangChainRuntimeDelegate(new LangChainRuntimeProperties());
        LangChainActivityEventCoordinator coordinator = new LangChainActivityEventCoordinator(event -> { });
        RuntimeModelConfig responses = new RuntimeModelConfig(
                "test-key", "SYSTEM", "https://example.test/v1", "gpt-test",
                "high", 128_000, RuntimeModelProtocol.RESPONSES, true);
        RuntimeModelConfig chatCompletions = new RuntimeModelConfig(
                "test-key", "SYSTEM", "https://api.deepseek.com/v1", "deepseek-v4",
                "medium", 128_000, RuntimeModelProtocol.CHAT_COMPLETIONS, null,
                false, "reasoning_content");
        RuntimeModelConfig kimi = new RuntimeModelConfig(
                "test-key", "SYSTEM", "https://api.moonshot.cn/v1", "k3-256k",
                "high", 262_144, RuntimeModelProtocol.CHAT_COMPLETIONS, null,
                false, "reasoning_content");
        RuntimeModelConfig genericChatCompletions = new RuntimeModelConfig(
                "test-key", "SYSTEM", "https://example.test/v1", "generic-chat-model",
                null, 128_000, RuntimeModelProtocol.CHAT_COMPLETIONS, false);
        try {
            assertThat(delegate.streamingModel(responses, 30, coordinator))
                    .isInstanceOfSatisfying(OpenAiResponsesStreamingChatModel.class, model -> {
                        OpenAiResponsesChatRequestParameters parameters =
                                (OpenAiResponsesChatRequestParameters) model.defaultRequestParameters();
                        assertThat(parameters.reasoningEffort()).isEqualTo("high");
                        assertThat(parameters.reasoningSummary()).isEqualTo("auto");
                    });
            assertThat(delegate.compactionModel(responses, 30))
                    .isInstanceOf(OpenAiResponsesChatModel.class);
            assertThat(delegate.streamingModel(chatCompletions, 30, coordinator))
                    .isInstanceOfSatisfying(OpenAiStreamingChatModel.class, model -> {
                        assertThat(model.defaultRequestParameters().reasoningEffort()).isEqualTo("medium");
                        assertThat(ReflectionTestUtils.getField(model, "returnThinking")).isEqualTo(true);
                        assertThat(ReflectionTestUtils.getField(model, "sendThinking")).isEqualTo(true);
                        assertThat(ReflectionTestUtils.getField(model, "thinkingFieldName"))
                                .isEqualTo("reasoning_content");
                    });
            assertThat(delegate.streamingModel(kimi, 30, coordinator))
                    .isInstanceOfSatisfying(OpenAiStreamingChatModel.class, model -> {
                        assertThat(model.defaultRequestParameters().reasoningEffort()).isEqualTo("high");
                        assertThat(ReflectionTestUtils.getField(model, "returnThinking")).isEqualTo(true);
                        assertThat(ReflectionTestUtils.getField(model, "sendThinking")).isEqualTo(true);
                        assertThat(ReflectionTestUtils.getField(model, "thinkingFieldName"))
                                .isEqualTo("reasoning_content");
                    });
            assertThat(delegate.streamingModel(genericChatCompletions, 30, coordinator))
                    .isInstanceOfSatisfying(OpenAiStreamingChatModel.class, model -> {
                        assertThat(ReflectionTestUtils.getField(model, "returnThinking")).isEqualTo(false);
                        assertThat(ReflectionTestUtils.getField(model, "sendThinking")).isEqualTo(false);
                    });
            assertThat(delegate.compactionModel(chatCompletions, 30))
                    .isInstanceOf(OpenAiChatModel.class);
        } finally {
            delegate.close();
        }
    }

    @Test
    void rendersCapabilityToolEventsWithCommandSemantics() throws Exception {
        LangChainCapabilityRegistry registry = registry(List.of(platformCommand()), List.of());
        DefaultLangChainRuntimeDelegate delegate = new DefaultLangChainRuntimeDelegate(new LangChainRuntimeProperties());
        try {
            RuntimeEvent running = delegate.toolEvent(
                    registry.nativeCommands().get(0).toolName(), "call-1",
                    "{\"arguments\":[\"--branch\",\"main\"]}",
                    null, RuntimeEventStatus.RUNNING, registry);
            RuntimeEvent completed = delegate.toolEvent(
                    registry.nativeCommands().get(0).toolName(), "call-1",
                    "{\"arguments\":[\"--branch\",\"main\"]}",
                    "ok", RuntimeEventStatus.SUCCESS, registry);

            assertThat(running.title()).isEqualTo("tool:resource-memory.search");
            assertThat(running.payload().actionLabel()).isEqualTo("检索资源记忆");
            assertThat(running.payload().actionTarget()).isEqualTo("resource-memory.search");
            assertThat(running.payload().actionInstanceId()).isEqualTo("call-1");
            assertThat(running.payload().actionIcon()).isNull();
            assertThat(completed.payload().actionKey()).isEqualTo(running.payload().actionKey());
            assertThat(completed.payload().actionInstanceId()).isEqualTo(running.payload().actionInstanceId());
        } finally {
            delegate.close();
        }
    }

    @Test
    void rendersPlatformResourceCommandEventsAsTools() throws Exception {
        LangChainCapabilityRegistry registry = registry(List.of(platformCommand()), List.of());
        DefaultLangChainRuntimeDelegate delegate = new DefaultLangChainRuntimeDelegate(new LangChainRuntimeProperties());
        try {
            RuntimeEvent event = delegate.toolEvent(
                    registry.nativeCommands().get(0).toolName(), "call-1", "{}",
                    "[]", RuntimeEventStatus.SUCCESS, registry);

            assertThat(event.title()).isEqualTo("tool:resource-memory.search");
            assertThat(event.payload().actionLabel()).isEqualTo("检索资源记忆");
            assertThat(event.payload().actionTarget()).isEqualTo("resource-memory.search");
        } finally {
            delegate.close();
        }
    }

    @Test
    void rendersSkillCommandEventsWithPlatformActionLabel() throws Exception {
        RuntimeCommandDescriptor command = new RuntimeCommandDescriptor(
                "project-hub", "project-hub.project-list", "project-hub project-list",
                "查询项目列表", null, workspace.resolve("private/project-hub").toString(),
                List.of("project-list"), List.of(), null, List.of(), RuntimeActionIcon.LIST_BULLETS);
        LangChainCapabilityRegistry registry = registry(List.of(command), List.of(
                new RuntimeSkillDescriptor("project-hub", "项目管理上下文", workspace.toString())));
        DefaultLangChainRuntimeDelegate delegate = new DefaultLangChainRuntimeDelegate(new LangChainRuntimeProperties());
        try {
            RuntimeEvent event = delegate.toolEvent(
                    "run_skill_command", "call-skill", """
                            {"command":"project-hub project-list",\
                            "arguments":["--environment","PROD"]}
                            """,
                    null, RuntimeEventStatus.RUNNING, registry);

            assertThat(event.title()).isEqualTo("capability:project-hub:project-list");
            assertThat(event.payload().toolName()).isEqualTo("run_skill_command");
            assertThat(event.payload().actionLabel()).isEqualTo("查询项目列表");
            assertThat(event.payload().actionTarget()).isEqualTo("project-hub project-list");
            assertThat(event.payload().actionIcon()).isEqualTo(RuntimeActionIcon.LIST_BULLETS);
            assertThat(event.payload().arguments())
                    .contains("--environment", "PROD")
                    .doesNotContain("project_hub.py", "scriptPath", "skillName");

            RuntimeEvent fallbackEvent = delegate.toolEvent(
                    "run_skill_command", "call-skill-fallback", """
                            {"command":"project-hub unknown",\
                            "arguments":[]}
                            """,
                    null, RuntimeEventStatus.RUNNING, registry);
            assertThat(fallbackEvent.payload().actionLabel()).isEqualTo("执行能力命令");
            assertThat(fallbackEvent.payload().actionTarget()).isNull();
            assertThat(fallbackEvent.payload().toolName()).isEqualTo("run_skill_command");
        } finally {
            delegate.close();
        }
    }

    @Test
    void rendersSkillReadsWithPlatformDisplayName() throws Exception {
        LangChainCapabilityRegistry registry = registry(List.of(), List.of(
                new RuntimeSkillDescriptor("project-hub", "项目上下文", workspace.toString())));
        DefaultLangChainRuntimeDelegate delegate = new DefaultLangChainRuntimeDelegate(new LangChainRuntimeProperties());
        try {
            RuntimeEvent event = delegate.toolEvent(
                    "read_skill_file", "call-skill-read", """
                            {"skillName":"project-hub","relativePath":"SKILL.md"}
                            """,
                    null, RuntimeEventStatus.RUNNING, registry);

            assertThat(event.payload().toolName()).isEqualTo("read_skill_file");
            assertThat(event.payload().actionLabel()).isEqualTo("读取项目上下文 SKILL");
            assertThat(event.payload().actionTarget()).isEqualTo("SKILL.md");
            assertThat(event.payload().actionIcon()).isEqualTo(RuntimeActionIcon.BOOK_OPEN);
        } finally {
            delegate.close();
        }
    }

    @Test
    void rendersGenericFileToolEvent() throws Exception {
        LangChainCapabilityRegistry registry = registry(List.of(), List.of());
        DefaultLangChainRuntimeDelegate delegate = new DefaultLangChainRuntimeDelegate(new LangChainRuntimeProperties());
        try {
            RuntimeEvent event = delegate.toolEvent(
                    "read_file", "call-file",
                    "{\"root\":\"task-context\",\"relativePath\":\"artifacts/contact-sheet.jpg\"}",
                    null, RuntimeEventStatus.RUNNING, registry);
            RuntimeEvent queryEvent = delegate.toolEvent(
                    "query_json", "call-json",
                    "{\"root\":\"task-context\",\"relativePath\":\"artifacts/results.json\",\"expression\":\"items\"}",
                    null, RuntimeEventStatus.RUNNING, registry);
            RuntimeEvent evidenceEvent = delegate.toolEvent(
                    "read_evidence", "call-evidence",
                    "{\"evidenceId\":\"ev-0123456789abcdef\",\"offset\":0}",
                    null, RuntimeEventStatus.RUNNING, registry);
            RuntimeEvent evidenceQueryEvent = delegate.toolEvent(
                    "query_evidence", "call-evidence-query",
                    "{\"evidenceId\":\"ev-0123456789abcdef\",\"expression\":\"items\"}",
                    null, RuntimeEventStatus.RUNNING, registry);

            assertThat(event.payload().actionLabel()).isEqualTo("读取文件");
            assertThat(event.payload().actionTarget()).isEqualTo("artifacts/contact-sheet.jpg");
            assertThat(event.payload().actionIcon()).isEqualTo(RuntimeActionIcon.FILE_TEXT);
            assertThat(queryEvent.payload().actionLabel()).isEqualTo("查询 JSON");
            assertThat(queryEvent.payload().actionTarget()).isEqualTo("artifacts/results.json");
            assertThat(queryEvent.payload().actionIcon()).isEqualTo(RuntimeActionIcon.BRACKETS_CURLY);
            assertThat(evidenceEvent.payload().actionLabel()).isEqualTo("读取上下文证据");
            assertThat(evidenceEvent.payload().actionTarget()).isEqualTo("ev-0123456789abcdef");
            assertThat(evidenceEvent.payload().actionIcon()).isEqualTo(RuntimeActionIcon.FILE_TEXT);
            assertThat(evidenceQueryEvent.payload().actionLabel()).isEqualTo("查询上下文证据");
            assertThat(evidenceQueryEvent.payload().actionTarget()).isEqualTo("ev-0123456789abcdef");
            assertThat(evidenceQueryEvent.payload().actionIcon()).isEqualTo(RuntimeActionIcon.BRACKETS_CURLY);
        } finally {
            delegate.close();
        }
    }

    @Test
    void rendersMcpGatewayInvocationAsTheSelectedNativeTool() {
        LangChainCapabilityRegistry registry = registry(List.of(), List.of());
        DefaultLangChainRuntimeDelegate delegate = new DefaultLangChainRuntimeDelegate(new LangChainRuntimeProperties());
        LangChainExecutionContext context = new LangChainExecutionContext(
                "mcp-event", workspace, event -> { });
        context.registerToolPresentation("search_code", "搜索代码", RuntimeActionIcon.CODE);
        try {
            RuntimeEvent event = delegate.toolEvent(
                    "invoke_mcp_tool", "call-mcp", """
                            {"mcpCode":"codebase-memory","toolName":"search_code",\
                            "arguments":{"project":"lingxi","pattern":"McpToolProvider"}}
                            """,
                    null, RuntimeEventStatus.RUNNING, registry, context);

            assertThat(event.title()).isEqualTo("mcp:codebase-memory:search_code");
            assertThat(event.payload().actionLabel()).isEqualTo("搜索代码");
            assertThat(event.payload().actionTarget()).isEqualTo("search_code");
            assertThat(event.payload().actionIcon()).isEqualTo(RuntimeActionIcon.CODE);
            assertThat(event.payload().arguments())
                    .contains("project", "lingxi", "pattern", "McpToolProvider")
                    .doesNotContain("mcpCode", "toolName");
        } finally {
            delegate.close();
        }
    }

    @Test
    void rendersDynamicToolPresentationWithoutKnowingExternalToolName() {
        LangChainCapabilityRegistry registry = registry(List.of(), List.of());
        LangChainExecutionContext context = new LangChainExecutionContext(
                "external-tool", workspace, event -> { });
        context.registerToolPresentation("list_projects", "查看索引项目", RuntimeActionIcon.CODE);
        DefaultLangChainRuntimeDelegate delegate = new DefaultLangChainRuntimeDelegate(new LangChainRuntimeProperties());
        try {
            RuntimeEvent event = delegate.toolEvent(
                    "list_projects", "call-mcp", "{}", null,
                    RuntimeEventStatus.RUNNING, registry, context);

            assertThat(event.payload().actionLabel()).isEqualTo("查看索引项目");
            assertThat(event.payload().actionIcon()).isEqualTo(RuntimeActionIcon.CODE);
        } finally {
            delegate.close();
        }
    }

    @Test
    void rejectsEmptyFinalModelResponse() {
        DefaultLangChainRuntimeDelegate delegate = new DefaultLangChainRuntimeDelegate(new LangChainRuntimeProperties());
        ChatResponse response = ChatResponse.builder()
                .id("response-empty")
                .modelName("test-model")
                .aiMessage(AiMessage.from(""))
                .finishReason(FinishReason.STOP)
                .tokenUsage(new TokenUsage(0, 0, 0))
                .build();
        try {
            assertThatThrownBy(() -> delegate.requireValidFinalResponse("execution-empty", response))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("模型返回空响应，请重试；若持续发生请检查模型服务兼容性")
                    .hasMessageNotContaining("LangChain");
        } finally {
            delegate.close();
        }
    }

    @Test
    void returnsRecoverableResultForUnknownToolName() {
        DefaultLangChainRuntimeDelegate delegate = new DefaultLangChainRuntimeDelegate(new LangChainRuntimeProperties());
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-unknown")
                .name("search_file")
                .arguments("{}")
                .build();
        try {
            var result = delegate.hallucinatedToolResult(request);

            assertThat(result.id()).isEqualTo("call-unknown");
            assertThat(result.toolName()).isEqualTo("search_file");
            assertThat(result.isError()).isTrue();
            assertThat(result.text()).contains("工具不存在", "当前已提供的工具定义");
        } finally {
            delegate.close();
        }
    }

    private RuntimeCommandDescriptor platformCommand() {
        return new RuntimeCommandDescriptor(
                null, "resource-memory.search", "resource-memory search", "检索资源记忆",
                null, workspace.resolve("private/resource-memory").toString(), List.of("search"), List.of());
    }

    private LangChainCapabilityRegistry registry(List<RuntimeCommandDescriptor> commands,
                                                 List<RuntimeSkillDescriptor> skills) {
        return new LangChainCapabilityRegistry(new RuntimeExecutionEnvironment(
                java.util.Map.of(), List.of(), List.of(), List.of(), commands, skills));
    }
}
