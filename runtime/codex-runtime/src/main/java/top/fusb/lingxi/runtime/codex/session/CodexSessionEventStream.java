package top.fusb.lingxi.runtime.codex.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.runtime.api.event.RuntimeActionIcon;
import top.fusb.lingxi.runtime.api.event.RuntimeEvent;
import top.fusb.lingxi.runtime.api.event.RuntimeEventPayload;
import top.fusb.lingxi.runtime.api.event.RuntimeEventSemantic;
import top.fusb.lingxi.runtime.api.event.RuntimeModelTimingMode;
import top.fusb.lingxi.runtime.api.event.RuntimeEventStatus;
import top.fusb.lingxi.runtime.api.event.RuntimeEventType;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpServerConfig;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpToolPresentation;
import lombok.extern.slf4j.Slf4j;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Slf4j
public class CodexSessionEventStream {

    private static final long POLL_INTERVAL_MILLIS = 200L;
    private static final Set<String> STDOUT_MANAGED_TOOLS = Set.of("exec_command");

    private final ObjectMapper objectMapper;

    public CodexSessionEventStream(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 记录执行开始前所有 Codex session 文件的长度，后续只读取本轮新增事件。
     *
     * @param codexHome 当前会话使用的 CODEX_HOME
     * @return 本轮 session 事件读取游标
     */
    public Cursor open(Path codexHome) {
        return open(codexHome, List.of());
    }

    /**
     * 创建 session 事件游标，并绑定本次执行的 MCP Tool 展示元数据。
     *
     * @param codexHome 当前会话使用的 CODEX_HOME
     * @param mcpServers 当前任务挂载的 MCP 配置
     * @return 本轮 session 事件读取游标
     */
    public Cursor open(Path codexHome, List<RuntimeMcpServerConfig> mcpServers) {
        Map<Path, Long> offsets = new HashMap<>();
        for (Path path : sessionFiles(codexHome)) {
            offsets.put(path, fileSize(path));
        }
        Map<String, RuntimeMcpToolPresentation> presentations = new HashMap<>();
        for (RuntimeMcpServerConfig server : mcpServers == null ? List.<RuntimeMcpServerConfig>of() : mcpServers) {
            server.toolPresentations().forEach(presentations::putIfAbsent);
        }
        return new Cursor(codexHome.toAbsolutePath().normalize(), offsets, presentations);
    }

    /**
     * 持续读取 Codex session 中未通过 CLI stdout 暴露的工具事件，并在进程结束后执行一次最终读取。
     *
     * @param cursor 执行开始前创建的 session 游标
     * @param running Codex 进程是否仍在运行
     * @param eventConsumer 统一运行事件消费者
     * @return 无返回值
     */
    public void watch(Cursor cursor, BooleanSupplier running, Consumer<RuntimeEvent> eventConsumer) {
        watch(cursor, running, new SessionObserver() {
            @Override
            public void onEvent(RuntimeEvent event) {
                eventConsumer.accept(event);
            }
        });
    }

    /**
     * 持续读取 session 原始事件，并分别暴露展示事件、模型指标、工具批次和原始行。
     *
     * @param cursor 执行开始前创建的 session 游标
     * @param running Codex 进程是否仍在运行
     * @param observer Codex Runtime 内部 session 观察器
     * @return 无返回值
     */
    public void watch(Cursor cursor, BooleanSupplier running, SessionObserver observer) {
        try {
            while (running.getAsBoolean()) {
                readAvailable(cursor, observer);
                Thread.sleep(POLL_INTERVAL_MILLIS);
            }
            readAvailable(cursor, observer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("读取 Codex session 工具事件失败 codexHome={} message={}", cursor.codexHome, e.getMessage());
        }
    }

    List<RuntimeEvent> readAvailable(Cursor cursor) {
        List<RuntimeEvent> events = new ArrayList<>();
        readAvailable(cursor, new SessionObserver() {
            @Override
            public void onEvent(RuntimeEvent event) {
                events.add(event);
            }
        });
        return events;
    }

    public void readAvailable(Cursor cursor, SessionObserver observer) {
        synchronized (cursor) {
            for (Path path : sessionFiles(cursor.codexHome)) {
                long offset = cursor.offsets.getOrDefault(path, 0L);
                long size = fileSize(path);
                if (size < offset) {
                    offset = 0L;
                }
                if (size == offset) {
                    cursor.offsets.put(path, offset);
                    continue;
                }
                cursor.offsets.put(path, readFile(path, offset, size, cursor, observer));
            }
        }
    }

    private long readFile(Path path, long offset, long size, Cursor cursor, SessionObserver observer) {
        long committedOffset = offset;
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            file.seek(offset);
            while (file.getFilePointer() < size) {
                long lineOffset = file.getFilePointer();
                String encodedLine = file.readLine();
                long nextOffset = file.getFilePointer();
                if (encodedLine == null || (nextOffset == size && !endsWithNewline(file, size))) {
                    break;
                }
                committedOffset = nextOffset;
                String line = new String(encodedLine.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                observer.onSessionLine(line);
                RuntimeEvent event = parseSessionLine(line, cursor, observer);
                if (event != null) {
                    observer.onEvent(event);
                }
                if (nextOffset <= lineOffset) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("读取 Codex session 文件失败 path={} offset={} message={}", path, offset, e.getMessage());
        }
        return committedOffset;
    }

    private boolean endsWithNewline(RandomAccessFile file, long size) throws Exception {
        if (size <= 0) {
            return false;
        }
        long position = file.getFilePointer();
        file.seek(size - 1);
        int lastByte = file.read();
        file.seek(position);
        return lastByte == '\n';
    }

    private RuntimeEvent parseSessionLine(String line, Cursor cursor, SessionObserver observer) {
        try {
            JsonNode root = objectMapper.readTree(line);
            String rootType = root.path("type").asText();
            JsonNode payload = root.path("payload");
            String timestamp = text(root, "timestamp");
            if ("turn_context".equals(rootType)) {
                cursor.model = text(payload, "model");
                return null;
            }
            if ("event_msg".equals(rootType)) {
                handleEventMessage(payload, timestamp, cursor, observer);
                return null;
            }
            if (!"response_item".equals(rootType)) {
                return null;
            }
            String type = payload.path("type").asText();
            handleModelResponseItem(payload, type, timestamp, cursor, observer);
            if ("custom_tool_call".equals(type)) {
                String callId = firstText(payload, "call_id", "id");
                if (callId != null) {
                    cursor.customToolCalls.add(callId);
                    observer.onToolBatchStarted(callId, currentRequestId(cursor));
                }
                return null;
            }
            if ("custom_tool_call_output".equals(type)) {
                String callId = firstText(payload, "call_id", "id");
                if (callId != null && cursor.customToolCalls.remove(callId)) {
                    observer.onToolBatchCompleted(callId);
                }
                cursor.nextModelRequestStartedAt = timestamp;
                return null;
            }
            if ("function_call".equals(type)) {
                String toolName = text(payload, "name");
                String callId = firstText(payload, "call_id", "id");
                if (callId != null && STDOUT_MANAGED_TOOLS.contains(toolName)) {
                    cursor.stdoutManagedToolCalls.add(callId);
                    observer.onToolBatchStarted(callId, currentRequestId(cursor));
                    return null;
                }
                RuntimeEvent event = startedEvent(payload, cursor);
                if (event != null) {
                    observer.onToolBatchStarted(callId, currentRequestId(cursor));
                }
                return event;
            }
            if ("function_call_output".equals(type)) {
                String callId = firstText(payload, "call_id", "id");
                if (callId != null && cursor.stdoutManagedToolCalls.remove(callId)) {
                    observer.onToolBatchCompleted(callId);
                    cursor.nextModelRequestStartedAt = timestamp;
                    return null;
                }
                RuntimeEvent event = completedEvent(payload, cursor);
                if (event != null) {
                    observer.onToolBatchCompleted(callId);
                }
                cursor.nextModelRequestStartedAt = timestamp;
                return event;
            }
            return null;
        } catch (Exception e) {
            log.debug("忽略无法解析的 Codex session 事件 bytes={} message={}",
                    line.getBytes(StandardCharsets.UTF_8).length, e.getMessage());
            return null;
        }
    }

    private void handleEventMessage(JsonNode payload, String timestamp, Cursor cursor, SessionObserver observer) {
        String type = payload.path("type").asText();
        if ("task_started".equals(type)) {
            cursor.turnId = firstText(payload, "turn_id", "id");
            startModelRequest(cursor, timestamp, observer);
            return;
        }
        if ("token_count".equals(type)) {
            completeModelRequest(cursor, payload.path("info"), timestamp, observer);
            if (cursor.nextModelRequestStartedAt != null) {
                String nextStartedAt = cursor.nextModelRequestStartedAt;
                cursor.nextModelRequestStartedAt = null;
                startModelRequest(cursor, nextStartedAt, observer);
            }
            return;
        }
        if ("mcp_tool_call_end".equals(type)) {
            completeMcpToolCall(payload, cursor, observer);
            return;
        }
        if ("task_complete".equals(type) && cursor.activeModelRequest != null
                && cursor.activeModelRequest.firstResponseAt != null) {
            completeModelRequest(cursor, null, timestamp, observer);
        }
    }

    private void handleModelResponseItem(JsonNode payload, String type, String timestamp,
                                         Cursor cursor, SessionObserver observer) {
        if (!isModelResponseItem(payload, type)) {
            return;
        }
        ModelRequest request = cursor.activeModelRequest;
        if (request == null && cursor.pendingModelRequest != null && isToolCall(type)) {
            request = cursor.pendingModelRequest;
        }
        if (request == null) {
            return;
        }
        if (request.firstResponseAt == null) {
            request.firstResponseAt = timestamp;
            observer.onEvent(modelMetric("runtime.model.request.first-response",
                    RuntimeEventSemantic.MODEL_REQUEST_FIRST_RESPONSE, RuntimeEventStatus.INFO,
                    request, timestamp, Map.of()));
        }
        request.lastResponseAt = timestamp;
        if (isToolCall(type)) {
            request.responseKind = "TOOL_CALL";
            request.toolRequestCount++;
            request.completedAt = timestamp;
            if (cursor.activeModelRequest == request) {
                cursor.pendingModelRequest = request;
                cursor.activeModelRequest = null;
            }
        } else if ("message".equals(type)) {
            request.responseKind = "ANSWER";
        }
    }

    private boolean isModelResponseItem(JsonNode payload, String type) {
        if ("reasoning".equals(type) || isToolCall(type)) {
            return true;
        }
        return "message".equals(type) && "assistant".equals(payload.path("role").asText());
    }

    private boolean isToolCall(String type) {
        return "function_call".equals(type) || "custom_tool_call".equals(type);
    }

    private void startModelRequest(Cursor cursor, String timestamp, SessionObserver observer) {
        if (timestamp == null || cursor.activeModelRequest != null) {
            return;
        }
        String prefix = cursor.turnId == null ? "codex" : cursor.turnId;
        ModelRequest request = new ModelRequest(prefix + ":model-request-" + (++cursor.modelRequestSequence), timestamp);
        cursor.activeModelRequest = request;
        observer.onEvent(modelMetric("runtime.model.request.started",
                RuntimeEventSemantic.MODEL_REQUEST_STARTED, RuntimeEventStatus.RUNNING,
                request, timestamp, Map.of()));
    }

    private void completeModelRequest(Cursor cursor, JsonNode info, String timestamp, SessionObserver observer) {
        ModelRequest request = cursor.pendingModelRequest != null ? cursor.pendingModelRequest : cursor.activeModelRequest;
        if (request == null) {
            return;
        }
        if (request.completedAt == null) {
            request.completedAt = request.lastResponseAt == null ? timestamp : request.lastResponseAt;
        }
        Map<String, String> metrics = new HashMap<>();
        put(metrics, "model", cursor.model);
        put(metrics, "timingSource", "SESSION_EVENTS");
        put(metrics, "responseKind", request.responseKind);
        if (request.toolRequestCount > 0) {
            put(metrics, "toolRequestCount", request.toolRequestCount);
        }
        appendUsageMetrics(metrics, info == null ? null : info.path("last_token_usage"));
        observer.onEvent(modelMetric("runtime.model.request.completed",
                RuntimeEventSemantic.MODEL_REQUEST_COMPLETED, RuntimeEventStatus.SUCCESS,
                request, request.completedAt, metrics));
        if (cursor.pendingModelRequest == request) {
            cursor.pendingModelRequest = null;
        } else {
            cursor.activeModelRequest = null;
        }
    }

    private void appendUsageMetrics(Map<String, String> metrics, JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) {
            return;
        }
        put(metrics, "inputTokens", number(usage, "input_tokens"));
        put(metrics, "cachedInputTokens", number(usage, "cached_input_tokens"));
        put(metrics, "outputTokens", number(usage, "output_tokens"));
        put(metrics, "reasoningOutputTokens", number(usage, "reasoning_output_tokens"));
        put(metrics, "totalTokens", number(usage, "total_tokens"));
    }

    private Long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : null;
    }

    private RuntimeEvent modelMetric(String rawType, RuntimeEventSemantic semantic, RuntimeEventStatus status, ModelRequest request,
                                     String eventTimestamp, Map<String, String> values) {
        Map<String, String> metrics = new HashMap<>(values);
        put(metrics, "eventTimestamp", eventTimestamp);
        put(metrics, "startedAt", request.startedAt);
        put(metrics, "firstResponseAt", request.firstResponseAt);
        put(metrics, "completedAt", request.completedAt);
        return new RuntimeEvent(
                RuntimeEventType.METRIC,
                status,
                rawType,
                null,
                new RuntimeEventPayload(
                        rawType, "model_request", request.id, status.name().toLowerCase(),
                        null, null, null, null, null, null, null,
                        "runtime:model-request", request.id, "模型 API 请求", "MEASURED", false,
                        metrics
                ).withSemantic(semantic)
                        .withModelTimingMode(RuntimeModelTimingMode.OBSERVED)
                        .withActionIcon(RuntimeActionIcon.PLUGS_CONNECTED)
        );
    }

    private String currentRequestId(Cursor cursor) {
        ModelRequest request = cursor.pendingModelRequest != null ? cursor.pendingModelRequest : cursor.activeModelRequest;
        return request == null ? null : request.id;
    }

    private void put(Map<String, String> values, String key, Object value) {
        if (value != null && !value.toString().isBlank()) {
            values.put(key, value.toString());
        }
    }

    private RuntimeEvent startedEvent(JsonNode payload, Cursor cursor) {
        String toolName = text(payload, "name");
        String callId = firstText(payload, "call_id", "id");
        if (toolName == null || callId == null || STDOUT_MANAGED_TOOLS.contains(toolName)) {
            return null;
        }
        String arguments = text(payload, "arguments");
        ToolCall call = new ToolCall(
                toolName, callId, arguments, actionTarget(arguments), cursor.toolPresentations.get(toolName));
        cursor.runningCalls.put(callId, call);
        return toolEvent(call, RuntimeEventStatus.RUNNING);
    }

    private RuntimeEvent completedEvent(JsonNode payload, Cursor cursor) {
        String callId = firstText(payload, "call_id", "id");
        ToolCall call = callId == null ? null : cursor.runningCalls.remove(callId);
        if (call == null) {
            return null;
        }
        boolean failed = payload.path("is_error").asBoolean(false)
                || payload.path("isError").asBoolean(false)
                || payload.hasNonNull("error")
                || "failed".equalsIgnoreCase(payload.path("status").asText());
        return toolEvent(call, failed ? RuntimeEventStatus.FAILED : RuntimeEventStatus.SUCCESS);
    }

    private void completeMcpToolCall(JsonNode payload, Cursor cursor, SessionObserver observer) {
        String callId = firstText(payload, "call_id", "id");
        ToolCall call = callId == null ? null : cursor.runningCalls.remove(callId);
        if (call == null) {
            return;
        }
        JsonNode result = payload.path("result");
        JsonNode success = result.path("Ok");
        boolean failed = result.has("Err") || success.path("isError").asBoolean(false);
        observer.onEvent(toolEvent(call, failed ? RuntimeEventStatus.FAILED : RuntimeEventStatus.SUCCESS,
                mcpResultText(result)));
        observer.onToolBatchCompleted(callId);
    }

    private String mcpResultText(JsonNode result) {
        JsonNode success = result.path("Ok");
        if (success.isMissingNode()) {
            JsonNode failure = result.path("Err");
            return failure.isMissingNode() ? null : failure.toString();
        }
        List<String> texts = new ArrayList<>();
        for (JsonNode item : success.path("content")) {
            String text = text(item, "text");
            if (text != null) {
                texts.add(text);
            }
        }
        if (!texts.isEmpty()) {
            return String.join(System.lineSeparator(), texts);
        }
        JsonNode structured = success.path("structuredContent");
        return structured.isMissingNode() || structured.isNull() ? null : structured.toString();
    }

    private RuntimeEvent toolEvent(ToolCall call, RuntimeEventStatus status) {
        return toolEvent(call, status, null);
    }

    private RuntimeEvent toolEvent(ToolCall call, RuntimeEventStatus status, String output) {
        String actionKey = "tool:" + call.toolName;
        RuntimeEventPayload payload = new RuntimeEventPayload(
                status == RuntimeEventStatus.RUNNING ? "function_call" : "function_call_output",
                null,
                null,
                status == RuntimeEventStatus.RUNNING ? "in_progress"
                        : status == RuntimeEventStatus.FAILED ? "failed" : "completed",
                null,
                call.toolName,
                call.callId,
                status == RuntimeEventStatus.RUNNING ? call.arguments : null,
                status == RuntimeEventStatus.RUNNING ? null : output,
                null,
                null,
                actionKey,
                call.callId,
                call.presentation == null ? null : call.presentation.label(),
                call.target,
                null
        ).withActionIcon(call.presentation == null ? null : call.presentation.icon());
        return new RuntimeEvent(RuntimeEventType.COMMAND, status, actionKey, null, payload);
    }

    private String actionTarget(String arguments) {
        if (arguments == null) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(arguments);
            for (String field : List.of("path", "file", "url", "uri", "query", "cmd")) {
                String value = text(root, field);
                if (value != null) {
                    return value;
                }
            }
        } catch (Exception ignored) {
            // 原始参数仍会保留在 payload 中，无法识别展示目标不影响工具事件本身。
        }
        return null;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank() || "null".equalsIgnoreCase(value.asText().trim())) {
            return null;
        }
        return value.asText();
    }

    private List<Path> sessionFiles(Path codexHome) {
        Path sessions = codexHome.toAbsolutePath().normalize().resolve("sessions");
        if (!Files.isDirectory(sessions)) {
            return List.of();
        }
        try (var paths = Files.walk(sessions)) {
            return paths
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jsonl"))
                    .map(path -> path.toAbsolutePath().normalize())
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (Exception e) {
            log.warn("扫描 Codex session 文件失败 codexHome={} message={}", codexHome, e.getMessage());
            return List.of();
        }
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception e) {
            return 0L;
        }
    }

    public static final class Cursor {

        private final Path codexHome;
        private final Map<Path, Long> offsets;
        private final Map<String, RuntimeMcpToolPresentation> toolPresentations;
        private final Map<String, ToolCall> runningCalls = new HashMap<>();
        private final Set<String> customToolCalls = new java.util.HashSet<>();
        private final Set<String> stdoutManagedToolCalls = new java.util.HashSet<>();
        private String turnId;
        private String model;
        private long modelRequestSequence;
        private ModelRequest activeModelRequest;
        private ModelRequest pendingModelRequest;
        private String nextModelRequestStartedAt;

        private Cursor(Path codexHome, Map<Path, Long> offsets,
                       Map<String, RuntimeMcpToolPresentation> toolPresentations) {
            this.codexHome = codexHome;
            this.offsets = offsets;
            this.toolPresentations = toolPresentations;
        }
    }

    private record ToolCall(String toolName, String callId, String arguments, String target,
                            RuntimeMcpToolPresentation presentation) {
    }

    private static final class ModelRequest {

        private final String id;
        private final String startedAt;
        private String firstResponseAt;
        private String lastResponseAt;
        private String completedAt;
        private String responseKind;
        private int toolRequestCount;

        private ModelRequest(String id, String startedAt) {
            this.id = id;
            this.startedAt = startedAt;
        }
    }

    public interface SessionObserver {

        default void onEvent(RuntimeEvent event) {
        }

        default void onSessionLine(String line) {
        }

        default void onToolBatchStarted(String callId, String modelRequestId) {
        }

        default void onToolBatchCompleted(String callId) {
        }
    }
}
