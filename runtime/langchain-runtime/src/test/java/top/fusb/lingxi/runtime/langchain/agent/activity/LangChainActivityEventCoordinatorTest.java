package top.fusb.lingxi.runtime.langchain.agent.activity;

import top.fusb.lingxi.runtime.api.event.RuntimeEvent;
import top.fusb.lingxi.runtime.api.event.RuntimeEventPayload;
import top.fusb.lingxi.runtime.api.event.RuntimeEventSemantic;
import top.fusb.lingxi.runtime.api.event.RuntimeEventStatus;
import top.fusb.lingxi.runtime.api.event.RuntimeEventType;
import top.fusb.lingxi.runtime.api.event.RuntimeModelTimingMode;
import top.fusb.lingxi.runtime.langchain.agent.LangChainExecutionLimitException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.output.TokenUsage;
import top.fusb.lingxi.runtime.langchain.agent.memory.LangChainTokenCountEstimator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LangChainActivityEventCoordinatorTest {

    @Test
    void keepsConcurrentToolsActiveUntilEveryToolCompletes() {
        List<RuntimeEvent> events = new ArrayList<>();
        LangChainActivityEventCoordinator coordinator = new LangChainActivityEventCoordinator(events::add);

        coordinator.onRequest(null);
        coordinator.toolStarted(tool("first-call", RuntimeEventStatus.RUNNING));
        coordinator.toolStarted(tool("second-call", RuntimeEventStatus.RUNNING));
        coordinator.toolCompleted(tool("first-call", RuntimeEventStatus.SUCCESS));

        assertThat(events).filteredOn(event -> event.type() != RuntimeEventType.METRIC)
                .extracting(RuntimeEvent::title).containsExactly(
                "langchain.reasoning", "tool:read_file", "tool:read_file", "tool:read_file");

        coordinator.toolCompleted(tool("second-call", RuntimeEventStatus.SUCCESS));

        assertThat(events.get(events.size() - 1).title()).isEqualTo("langchain.tool-call.generating");
        assertThat(events.get(events.size() - 1).payload().actionLabel()).isEqualTo("生成工具调用");
    }

    @Test
    void reflectsModelGenerationToolResultProcessingAndFinalization() {
        List<RuntimeEvent> events = new ArrayList<>();
        LangChainActivityEventCoordinator coordinator = new LangChainActivityEventCoordinator(events::add);

        coordinator.preparing();
        coordinator.prepared();
        coordinator.onRequest(null);
        coordinator.modelTextReceived();
        coordinator.toolStarted(tool("file-call", RuntimeEventStatus.RUNNING));
        coordinator.toolCompleted(tool("file-call", RuntimeEventStatus.SUCCESS));
        coordinator.intermediateResponseHandled(true);
        coordinator.onRequest(null);
        coordinator.modelTextReceived();
        coordinator.finalResponseHandled(true);

        assertThat(events).filteredOn(event -> event.type() != RuntimeEventType.METRIC)
                .extracting(RuntimeEvent::title).containsExactly(
                "langchain.runtime.preparing",
                "langchain.runtime.preparing",
                "langchain.reasoning",
                "langchain.response.generating",
                "tool:read_file",
                "tool:read_file",
                "langchain.tool-call.generating",
                "langchain.tool-calls.processing",
                "langchain.reasoning",
                "langchain.response.generating",
                "langchain.result.finalizing");
        assertThat(events.get(1).status()).isEqualTo(RuntimeEventStatus.SUCCESS);
        assertThat(events.get(1).payload().transientEvent()).isFalse();
        assertThat(events.get(events.size() - 1).payload().transientEvent()).isTrue();
    }

    @Test
    void emitsToolEventsAfterTheIntermediateMessageBoundary() {
        List<RuntimeEvent> events = new ArrayList<>();
        LangChainActivityEventCoordinator coordinator = new LangChainActivityEventCoordinator(events::add);

        coordinator.onRequest(null);
        coordinator.modelToolCallReceived();
        coordinator.toolStarted(tool("file-call", RuntimeEventStatus.RUNNING));

        assertThat(events).filteredOn(event -> event.type() != RuntimeEventType.METRIC).extracting(RuntimeEvent::title)
                .containsExactly("langchain.reasoning", "langchain.tool-call.generating");

        coordinator.intermediateResponseHandled(true);
        coordinator.toolCompleted(tool("file-call", RuntimeEventStatus.SUCCESS));

        assertThat(events).filteredOn(event -> event.type() != RuntimeEventType.METRIC)
                .extracting(RuntimeEvent::title).containsExactly(
                "langchain.reasoning",
                "langchain.tool-call.generating",
                "tool:read_file",
                "tool:read_file",
                "langchain.tool-call.generating");
    }

    @Test
    void emitsMeasuredModelRequestBoundaries() {
        List<RuntimeEvent> events = new ArrayList<>();
        LangChainActivityEventCoordinator coordinator = new LangChainActivityEventCoordinator(events::add);
        ChatRequest request = ChatRequest.builder()
                .modelName("deepseek-test")
                .messages(UserMessage.from("分析问题"))
                .build();
        ChatResponse response = ChatResponse.builder()
                .modelName("deepseek-test")
                .aiMessage(AiMessage.from("分析结果"))
                .tokenUsage(new TokenUsage(1200, 80, 1280))
                .build();

        coordinator.onRequest(new ChatModelRequestContext(request, null, Map.of()));
        coordinator.modelTextReceived();
        coordinator.onResponse(new ChatModelResponseContext(response, request, null, Map.of()));

        List<RuntimeEvent> metricEvents = events.stream()
                .filter(event -> event.type() == RuntimeEventType.METRIC).toList();
        assertThat(metricEvents)
                .extracting(event -> event.payload().rawType())
                .containsExactly(
                        "runtime.model.request.started",
                        "runtime.model.request.first-response",
                        "runtime.model.request.completed");
        assertThat(metricEvents)
                .extracting(event -> event.payload().semantic())
                .containsExactly(
                        RuntimeEventSemantic.MODEL_REQUEST_STARTED,
                        RuntimeEventSemantic.MODEL_REQUEST_FIRST_RESPONSE,
                        RuntimeEventSemantic.MODEL_REQUEST_COMPLETED);
        assertThat(metricEvents)
                .extracting(event -> event.payload().modelTimingMode())
                .containsOnly(RuntimeModelTimingMode.STREAMING);
        assertThat(metricEvents)
                .extracting(event -> event.payload().actionInstanceId()).containsOnly("model-request-1");
        assertThat(metricEvents.get(0).payload().metrics())
                .containsEntry("model", "deepseek-test")
                .containsEntry("messageCount", "1");
        assertThat(metricEvents.get(2).payload().metrics())
                .containsEntry("inputTokens", "1200")
                .containsEntry("outputTokens", "80")
                .containsEntry("totalTokens", "1280")
                .containsEntry("responseKind", "ANSWER");
    }

    @Test
    void recordsExclusiveInputTokenBreakdown(@TempDir Path tempDir) throws Exception {
        List<RuntimeEvent> events = new ArrayList<>();
        LangChainActivityEventCoordinator coordinator = new LangChainActivityEventCoordinator(
                "task-1", events::add, "model-request",
                new LangChainTokenCountEstimator("gpt-5", 256), "任务规则", "MCP 规则",
                tempDir, true);
        ToolExecutionResultMessage toolResult = ToolExecutionResultMessage.builder()
                .id("tool-1")
                .toolName("read_file")
                .contents(List.of(TextContent.from("工具输出"), ImageContent.from("aW1hZ2U=", "image/png")))
                .build();
        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from("平台规则\n\n任务规则\n\nMCP 规则"),
                        UserMessage.from("继续分析"), toolResult)
                .toolSpecifications(ToolSpecification.builder().name("read_file").build())
                .build();

        coordinator.onRequest(new ChatModelRequestContext(request, null, Map.of()));

        Map<String, String> metrics = events.stream()
                .filter(event -> event.type() == RuntimeEventType.METRIC)
                .findFirst().orElseThrow().payload().metrics();
        assertThat(Long.parseLong(metrics.get("systemInstructionTokens"))).isPositive();
        assertThat(Long.parseLong(metrics.get("taskInstructionTokens"))).isPositive();
        assertThat(Long.parseLong(metrics.get("mcpInstructionTokens"))).isPositive();
        assertThat(Long.parseLong(metrics.get("toolSchemaTokens"))).isPositive();
        assertThat(Long.parseLong(metrics.get("conversationTokens"))).isPositive();
        assertThat(Long.parseLong(metrics.get("toolResultTokens"))).isPositive();
        assertThat(metrics).containsEntry("imageTokens", "256");
        long classified = List.of("systemInstructionTokens", "taskInstructionTokens", "mcpInstructionTokens",
                        "toolSchemaTokens", "conversationTokens", "toolResultTokens", "imageTokens")
                .stream().mapToLong(key -> Long.parseLong(metrics.get(key))).sum();
        assertThat(classified).isEqualTo(Long.parseLong(metrics.get("estimatedInputTokens")));
        assertThat(tempDir.resolve("model-request-1.md")).exists();
        String snapshot = Files.readString(tempDir.resolve("model-request-1.md"));
        assertThat(snapshot).contains("## 系统基础指令", "## 任务/场景指令", "任务规则",
                "## MCP 指令", "MCP 规则", "## 工具 Schema", "read_file",
                "## 对话历史", "继续分析", "## 工具结果", "工具输出", "## 图片", "image/png");
    }

    @Test
    void assignsOneModelResponseBatchToAllConcurrentToolEvents() {
        List<RuntimeEvent> events = new ArrayList<>();
        LangChainActivityEventCoordinator coordinator = new LangChainActivityEventCoordinator(events::add);
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("分析问题")).build();
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(
                        toolRequest("first-call"),
                        toolRequest("second-call")
                )))
                .build();

        coordinator.onRequest(new ChatModelRequestContext(request, null, Map.of()));
        coordinator.modelToolCallReceived();
        coordinator.toolStarted(tool("first-call", RuntimeEventStatus.RUNNING));
        coordinator.toolStarted(tool("second-call", RuntimeEventStatus.RUNNING));
        coordinator.onResponse(new ChatModelResponseContext(response, request, null, Map.of()));
        coordinator.intermediateResponseHandled(false);
        coordinator.toolCompleted(tool("first-call", RuntimeEventStatus.SUCCESS));
        coordinator.toolCompleted(tool("second-call", RuntimeEventStatus.SUCCESS));

        assertThat(events).filteredOn(event -> "langchain.tool".equals(event.payload().rawType()))
                .extracting(event -> event.payload().actionGroupId())
                .containsOnly("langchain:model-request-1");
        assertThat(events).filteredOn(event -> "langchain.tool".equals(event.payload().rawType()))
                .extracting(event -> event.payload().actionGroupSize())
                .containsOnly(2);
    }

    @Test
    void closesRunningToolWhenExecutionIsCancelled() {
        List<RuntimeEvent> events = new ArrayList<>();
        LangChainActivityEventCoordinator coordinator = new LangChainActivityEventCoordinator(events::add);

        coordinator.toolStarted(tool("skill-call", RuntimeEventStatus.RUNNING));
        coordinator.executionCancelled();
        coordinator.toolCompleted(tool("skill-call", RuntimeEventStatus.SUCCESS));

        assertThat(events).filteredOn(event -> "tool:read_file".equals(event.title()))
                .extracting(RuntimeEvent::status)
                .containsExactly(RuntimeEventStatus.RUNNING, RuntimeEventStatus.FAILED);
        RuntimeEvent failed = events.stream()
                .filter(event -> event.status() == RuntimeEventStatus.FAILED)
                .findFirst()
                .orElseThrow();
        assertThat(failed.payload().actionInstanceId()).isEqualTo("skill-call");
        assertThat(failed.payload().output()).isEqualTo("任务已取消，操作未完成");
    }

    @Test
    void closesRunningToolsAndIgnoresLateCallbacksDuringTimeFinalization() {
        List<RuntimeEvent> events = new ArrayList<>();
        LangChainActivityEventCoordinator coordinator = new LangChainActivityEventCoordinator(events::add);

        coordinator.onRequest(null);
        coordinator.toolStarted(tool("slow-call", RuntimeEventStatus.RUNNING));
        coordinator.timeFinalizationStarted();
        coordinator.toolCompleted(tool("slow-call", RuntimeEventStatus.SUCCESS));
        coordinator.toolStarted(tool("late-call", RuntimeEventStatus.RUNNING));
        ChatRequest lateRequest = ChatRequest.builder().messages(UserMessage.from("late request")).build();
        coordinator.onError(new ChatModelErrorContext(
                new IllegalStateException("late cancellation"), lateRequest, null, Map.of()));

        assertThat(events).filteredOn(event -> "tool:read_file".equals(event.title()))
                .extracting(RuntimeEvent::status)
                .containsExactly(RuntimeEventStatus.RUNNING, RuntimeEventStatus.FAILED);
        assertThat(events).filteredOn(event -> event.status() == RuntimeEventStatus.FAILED
                        && "tool:read_file".equals(event.title()))
                .extracting(event -> event.payload().output())
                .containsExactly("达到探索时间上限，已停止继续调查");
        assertThat(events.get(events.size() - 1).title()).isEqualTo("langchain.result.finalizing");
        assertThat(events).filteredOn(event -> "runtime.model.request.failed".equals(event.payload().rawType()))
                .singleElement()
                .satisfies(event -> assertThat(event.payload().metrics())
                        .containsEntry("errorType", "TIME_LIMIT"));
    }

    @Test
    void hidesImplementationNameFromModelErrorMetrics() {
        List<RuntimeEvent> events = new ArrayList<>();
        LangChainActivityEventCoordinator coordinator = new LangChainActivityEventCoordinator(events::add);
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("分析问题")).build();

        coordinator.onRequest(new ChatModelRequestContext(request, null, Map.of()));
        coordinator.onError(new ChatModelErrorContext(
                new LangChainExecutionLimitException("LangChain Agent 已达到执行上限"),
                request, null, Map.of()));

        RuntimeEvent failed = events.stream()
                .filter(event -> "runtime.model.request.failed".equals(event.payload().rawType()))
                .findFirst()
                .orElseThrow();
        assertThat(failed.payload().metrics())
                .containsEntry("errorType", "MODEL_REQUEST_ERROR")
                .containsEntry("errorMessage", "任务已达到执行上限");
        assertThat(failed.payload().metrics().values()).allSatisfy(value ->
                assertThat(value).doesNotContainIgnoringCase("langchain"));
    }

    private RuntimeEvent tool(String callId, RuntimeEventStatus status) {
        RuntimeEventPayload payload = new RuntimeEventPayload(
                "langchain.tool", "tool_call", callId, status.name().toLowerCase(), null,
                "read_file", callId, null, null, null, null,
                "tool:read_file", callId, "读取文件", null, false
        );
        return new RuntimeEvent(RuntimeEventType.COMMAND, status, "tool:read_file", null, payload);
    }

    private ToolExecutionRequest toolRequest(String callId) {
        return ToolExecutionRequest.builder()
                .id(callId)
                .name("read_file")
                .arguments("{}")
                .build();
    }
}
