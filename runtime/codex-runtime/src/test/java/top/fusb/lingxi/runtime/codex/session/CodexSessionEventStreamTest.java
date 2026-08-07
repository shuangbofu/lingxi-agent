package top.fusb.lingxi.runtime.codex.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.runtime.api.event.RuntimeEvent;
import top.fusb.lingxi.runtime.api.event.RuntimeActionIcon;
import top.fusb.lingxi.runtime.api.event.RuntimeEventSemantic;
import top.fusb.lingxi.runtime.api.event.RuntimeEventStatus;
import top.fusb.lingxi.runtime.api.event.RuntimeModelTimingMode;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpServerConfig;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpToolPresentation;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexSessionEventStreamTest {

    private final CodexSessionEventStream stream = new CodexSessionEventStream(new ObjectMapper());

    @TempDir
    Path tempDir;

    @Test
    void shouldReadNewToolCallsWithoutReplayingExistingSessionEvents() throws Exception {
        Path session = sessionFile("rollout.jsonl");
        Files.writeString(session, call("old-call", "view_image", "{\"path\":\"/tmp/old.jpg\"}") + output("old-call"));
        CodexSessionEventStream.Cursor cursor = stream.open(tempDir);

        append(session, call("new-call", "view_image", "{\"path\":\"/tmp/新图片.jpg\",\"detail\":\"original\"}"));
        List<RuntimeEvent> started = stream.readAvailable(cursor);
        append(session, output("new-call"));
        List<RuntimeEvent> completed = stream.readAvailable(cursor);

        assertEquals(1, started.size());
        assertEquals(RuntimeEventStatus.RUNNING, started.get(0).status());
        assertEquals("tool:view_image", started.get(0).payload().actionKey());
        assertEquals("new-call", started.get(0).payload().actionInstanceId());
        assertEquals("/tmp/新图片.jpg", started.get(0).payload().actionTarget());
        assertEquals(1, completed.size());
        assertEquals(RuntimeEventStatus.SUCCESS, completed.get(0).status());
        assertNull(completed.get(0).payload().output());
        assertNull(completed.get(0).payload().arguments());
    }

    @Test
    void shouldReadAnySessionToolExceptCommandsAlreadyManagedByStdout() throws Exception {
        CodexSessionEventStream.Cursor cursor = stream.open(tempDir);
        Path session = sessionFile("new-rollout.jsonl");
        Files.writeString(session,
                call("command-call", "exec_command", "{\"cmd\":\"pwd\"}")
                        + call("search-call", "custom_search", "{\"query\":\"运行日志\"}")
                        + output("command-call")
                        + output("search-call"));

        List<RuntimeEvent> events = stream.readAvailable(cursor);

        assertEquals(2, events.size());
        assertEquals(List.of(RuntimeEventStatus.RUNNING, RuntimeEventStatus.SUCCESS),
                events.stream().map(RuntimeEvent::status).toList());
        assertTrue(events.stream().allMatch(event -> "tool:custom_search".equals(event.payload().actionKey())));
        assertEquals("运行日志", events.get(0).payload().actionTarget());
    }

    @Test
    void shouldWaitForACompleteJsonlLine() throws Exception {
        CodexSessionEventStream.Cursor cursor = stream.open(tempDir);
        Path session = sessionFile("partial.jsonl");
        String line = call("partial-call", "view_image", "{\"path\":\"/tmp/frame.jpg\"}");
        Files.writeString(session, line.substring(0, line.length() - 1), StandardCharsets.UTF_8);

        assertTrue(stream.readAvailable(cursor).isEmpty());

        append(session, "\n");
        List<RuntimeEvent> events = stream.readAvailable(cursor);
        assertEquals(1, events.size());
        assertEquals("partial-call", events.get(0).payload().callId());
    }

    @Test
    void shouldPreserveAnExplicitToolFailure() throws Exception {
        CodexSessionEventStream.Cursor cursor = stream.open(tempDir);
        Path session = sessionFile("failed.jsonl");
        Files.writeString(session,
                call("failed-call", "custom_tool", "{}")
                        + "{\"type\":\"response_item\",\"payload\":{\"type\":\"function_call_output\","
                        + "\"call_id\":\"failed-call\",\"is_error\":true,\"output\":\"secret error output\"}}\n");

        List<RuntimeEvent> events = stream.readAvailable(cursor);

        assertEquals(2, events.size());
        assertEquals(RuntimeEventStatus.FAILED, events.get(1).status());
        assertNull(events.get(1).payload().output());
    }

    @Test
    void shouldExposeIncrementalModelMetricsAndNativeToolBatchBoundaries() throws Exception {
        CodexSessionEventStream.Cursor cursor = stream.open(tempDir);
        Path session = sessionFile("model-metrics.jsonl");
        List<RuntimeEvent> events = new ArrayList<>();
        List<String> startedBatches = new ArrayList<>();
        List<String> completedBatches = new ArrayList<>();
        CodexSessionEventStream.SessionObserver observer = new CodexSessionEventStream.SessionObserver() {
            @Override
            public void onEvent(RuntimeEvent event) {
                events.add(event);
            }

            @Override
            public void onToolBatchStarted(String callId, String modelRequestId) {
                startedBatches.add(callId + ":" + modelRequestId);
            }

            @Override
            public void onToolBatchCompleted(String callId) {
                completedBatches.add(callId);
            }
        };
        Files.writeString(session, """
                {"timestamp":"2026-07-26T11:48:37.517Z","type":"event_msg","payload":{"type":"task_started","turn_id":"turn-137"}}
                {"timestamp":"2026-07-26T11:48:37.520Z","type":"turn_context","payload":{"model":"gpt-5.6-luna"}}
                {"timestamp":"2026-07-26T11:48:40.626Z","type":"response_item","payload":{"type":"reasoning"}}
                {"timestamp":"2026-07-26T11:48:43.563Z","type":"response_item","payload":{"type":"custom_tool_call","call_id":"call-1","name":"exec"}}
                {"timestamp":"2026-07-26T11:48:46.800Z","type":"response_item","payload":{"type":"custom_tool_call_output","call_id":"call-1"}}
                {"timestamp":"2026-07-26T11:48:46.800Z","type":"event_msg","payload":{"type":"token_count","info":{"last_token_usage":{"input_tokens":19378,"cached_input_tokens":11520,"output_tokens":185,"reasoning_output_tokens":44,"total_tokens":19563}}}}
                {"timestamp":"2026-07-26T11:48:51.339Z","type":"response_item","payload":{"type":"reasoning"}}
                {"timestamp":"2026-07-26T11:48:54.000Z","type":"response_item","payload":{"type":"message","role":"assistant"}}
                {"timestamp":"2026-07-26T11:48:54.100Z","type":"event_msg","payload":{"type":"token_count","info":{"last_token_usage":{"input_tokens":20842,"cached_input_tokens":19200,"output_tokens":96,"reasoning_output_tokens":7,"total_tokens":20938}}}}
                {"timestamp":"2026-07-26T11:48:54.101Z","type":"event_msg","payload":{"type":"task_complete","turn_id":"turn-137"}}
                """, StandardCharsets.UTF_8);

        stream.readAvailable(cursor, observer);

        List<RuntimeEvent> metrics = events.stream().filter(event -> "METRIC".equals(event.type().name())).toList();
        assertEquals(List.of(
                        "runtime.model.request.started", "runtime.model.request.first-response", "runtime.model.request.completed",
                        "runtime.model.request.started", "runtime.model.request.first-response", "runtime.model.request.completed"),
                metrics.stream().map(RuntimeEvent::title).toList());
        assertEquals(List.of(
                        RuntimeEventSemantic.MODEL_REQUEST_STARTED,
                        RuntimeEventSemantic.MODEL_REQUEST_FIRST_RESPONSE,
                        RuntimeEventSemantic.MODEL_REQUEST_COMPLETED,
                        RuntimeEventSemantic.MODEL_REQUEST_STARTED,
                        RuntimeEventSemantic.MODEL_REQUEST_FIRST_RESPONSE,
                        RuntimeEventSemantic.MODEL_REQUEST_COMPLETED),
                metrics.stream().map(event -> event.payload().semantic()).toList());
        assertTrue(metrics.stream().allMatch(event ->
                event.payload().modelTimingMode() == RuntimeModelTimingMode.OBSERVED));
        assertEquals("19378", metrics.get(2).payload().metrics().get("inputTokens"));
        assertEquals("gpt-5.6-luna", metrics.get(2).payload().metrics().get("model"));
        assertEquals("TOOL_CALL", metrics.get(2).payload().metrics().get("responseKind"));
        assertEquals("ANSWER", metrics.get(5).payload().metrics().get("responseKind"));
        assertEquals(1, startedBatches.size());
        assertTrue(startedBatches.get(0).startsWith("call-1:turn-137:model-request-1"));
        assertEquals(List.of("call-1"), completedBatches);
    }

    @Test
    void shouldExposeBatchBoundariesForStdoutManagedFunctionCallsWithoutDuplicatingEvents() throws Exception {
        CodexSessionEventStream.Cursor cursor = stream.open(tempDir);
        Path session = sessionFile("stdout-tools.jsonl");
        List<RuntimeEvent> events = new ArrayList<>();
        List<String> startedBatches = new ArrayList<>();
        List<String> completedBatches = new ArrayList<>();
        CodexSessionEventStream.SessionObserver observer = new CodexSessionEventStream.SessionObserver() {
            @Override
            public void onEvent(RuntimeEvent event) {
                events.add(event);
            }

            @Override
            public void onToolBatchStarted(String callId, String modelRequestId) {
                startedBatches.add(callId + ":" + modelRequestId);
            }

            @Override
            public void onToolBatchCompleted(String callId) {
                completedBatches.add(callId);
            }
        };
        Files.writeString(session, """
                {"timestamp":"2026-08-01T12:00:00Z","type":"event_msg","payload":{"type":"task_started","turn_id":"turn-203"}}
                {"timestamp":"2026-08-01T12:00:01Z","type":"response_item","payload":{"type":"function_call","name":"exec_command","arguments":"{\\"cmd\\":\\"rg foo\\"}","call_id":"exec-1"}}
                {"timestamp":"2026-08-01T12:00:01Z","type":"response_item","payload":{"type":"function_call","name":"exec_command","arguments":"{\\"cmd\\":\\"sed -n 1,20p a\\"}","call_id":"exec-2"}}
                {"timestamp":"2026-08-01T12:00:02Z","type":"response_item","payload":{"type":"function_call_output","call_id":"exec-1","output":"ignored"}}
                {"timestamp":"2026-08-01T12:00:02Z","type":"response_item","payload":{"type":"function_call_output","call_id":"exec-2","output":"ignored"}}
                """, StandardCharsets.UTF_8);

        stream.readAvailable(cursor, observer);

        assertTrue(events.stream().noneMatch(event -> "COMMAND".equals(event.type().name())));
        assertEquals(List.of(
                "exec-1:turn-203:model-request-1", "exec-2:turn-203:model-request-1"), startedBatches);
        assertEquals(List.of("exec-1", "exec-2"), completedBatches);
    }

    @Test
    void shouldCompleteMcpToolCallsFromSessionEvents() throws Exception {
        CodexSessionEventStream.Cursor cursor = stream.open(tempDir);
        Path session = sessionFile("mcp-tools.jsonl");
        Files.writeString(session, """
                {"timestamp":"2026-08-01T12:00:00Z","type":"event_msg","payload":{"type":"task_started","turn_id":"turn-212"}}
                {"timestamp":"2026-08-01T12:00:01Z","type":"response_item","payload":{"type":"function_call","name":"list_mcp_resources","arguments":"{}","call_id":"mcp-1"}}
                {"timestamp":"2026-08-01T12:00:02Z","type":"event_msg","payload":{"type":"mcp_tool_call_end","call_id":"mcp-1","invocation":{"server":"codex","tool":"list_mcp_resources","arguments":{}},"result":{"Ok":{"content":[{"type":"text","text":"{\\"resources\\":[]}"}],"isError":false}}}}
                {"timestamp":"2026-08-01T12:00:03Z","type":"response_item","payload":{"type":"function_call_output","call_id":"mcp-1","output":"{\\"resources\\":[]}"}}
                """, StandardCharsets.UTF_8);

        List<RuntimeEvent> events = stream.readAvailable(cursor);

        List<RuntimeEvent> toolEvents = events.stream()
                .filter(event -> "tool:list_mcp_resources".equals(event.payload().actionKey()))
                .toList();
        assertEquals(2, toolEvents.size());
        assertEquals(List.of(RuntimeEventStatus.RUNNING, RuntimeEventStatus.SUCCESS),
                toolEvents.stream().map(RuntimeEvent::status).toList());
        assertEquals("{\"resources\":[]}", toolEvents.get(1).payload().output());
    }

    @Test
    void shouldUseConfiguredMcpToolPresentation() throws Exception {
        RuntimeMcpServerConfig server = new RuntimeMcpServerConfig(
                "code-memory", "Code Memory", null, RuntimeMcpTransport.STREAMABLE_HTTP,
                null, List.of(), "https://example.test/mcp", Map.of(), Map.of(), Set.of(), Set.of(),
                Map.of("list_projects", new RuntimeMcpToolPresentation("查看索引项目", RuntimeActionIcon.CODE)));
        CodexSessionEventStream.Cursor cursor = stream.open(tempDir, List.of(server));
        Path session = sessionFile("configured-mcp-tool.jsonl");
        Files.writeString(session, call("mcp-config-1", "list_projects", "{}")
                + output("mcp-config-1"), StandardCharsets.UTF_8);

        List<RuntimeEvent> events = stream.readAvailable(cursor);

        assertEquals("查看索引项目", events.get(0).payload().actionLabel());
        assertEquals(RuntimeActionIcon.CODE, events.get(0).payload().actionIcon());
    }

    private Path sessionFile(String name) throws Exception {
        Path directory = tempDir.resolve("sessions/2026/07/25");
        Files.createDirectories(directory);
        return directory.resolve(name);
    }

    private void append(Path path, String value) throws Exception {
        Files.writeString(path, value, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private String call(String callId, String name, String arguments) {
        return "{\"type\":\"response_item\",\"payload\":{\"type\":\"function_call\",\"name\":\""
                + name + "\",\"arguments\":" + quote(arguments) + ",\"call_id\":\"" + callId + "\"}}\n";
    }

    private String output(String callId) {
        return "{\"type\":\"response_item\",\"payload\":{\"type\":\"function_call_output\",\"call_id\":\""
                + callId + "\",\"output\":\"ignored image bytes\"}}\n";
    }

    private String quote(String value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
