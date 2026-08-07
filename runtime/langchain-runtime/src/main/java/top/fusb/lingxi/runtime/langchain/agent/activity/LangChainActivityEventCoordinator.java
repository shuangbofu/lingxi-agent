package top.fusb.lingxi.runtime.langchain.agent.activity;

import top.fusb.lingxi.runtime.langchain.util.LangChainErrorMessageKit;
import top.fusb.lingxi.runtime.langchain.agent.memory.LangChainTokenCountEstimator;
import top.fusb.lingxi.runtime.langchain.agent.model.LangChainContextBudget;
import top.fusb.lingxi.runtime.api.event.RuntimeEvent;
import top.fusb.lingxi.runtime.api.event.RuntimeEventPayload;
import top.fusb.lingxi.runtime.api.event.RuntimeEventSemantic;
import top.fusb.lingxi.runtime.api.event.RuntimeModelTimingMode;
import top.fusb.lingxi.runtime.api.event.RuntimeEventStatus;
import top.fusb.lingxi.runtime.api.event.RuntimeEventType;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 根据 LangChain4j 的模型、流式响应和工具回调维护当前真实活动状态。
 */
@Slf4j
public final class LangChainActivityEventCoordinator implements ChatModelListener {

    private static final String THINKING_STATE = "langchain.reasoning";
    private final String executionId;
    private final Consumer<RuntimeEvent> eventConsumer;
    private final String requestIdPrefix;
    private final LangChainContextBudget contextBudget;
    private final LangChainRequestContentSnapshotWriter requestSnapshotWriter;
    private final boolean activityEventsEnabled;
    private final Map<String, RuntimeEvent> runningTools = new LinkedHashMap<>();
    private final List<RuntimeEvent> anonymousRunningTools = new ArrayList<>();
    private final List<RuntimeEvent> deferredToolEvents = new ArrayList<>();
    private boolean modelRequestRunning;
    private long modelRequestSequence;
    private String currentModelRequestId;
    private String currentToolBatchId;
    private int currentToolBatchSize;
    private boolean firstModelResponseReceived;
    private boolean deferToolEventsUntilIntermediateResponse;
    private String currentTransientState;
    private boolean executionTerminated;

    /**
     * 创建单次 LangChain 执行的活动状态协调器。
     *
     * @param eventConsumer Runtime 事件输出目标
     */
    public LangChainActivityEventCoordinator(Consumer<RuntimeEvent> eventConsumer) {
        this("langchain", eventConsumer);
    }

    /**
     * 创建带执行标识的活动状态协调器，用于区分不同执行中的模型请求和工具批次。
     *
     * @param executionId 当前 Runtime 执行标识
     * @param eventConsumer Runtime 事件输出目标
     */
    public LangChainActivityEventCoordinator(String executionId, Consumer<RuntimeEvent> eventConsumer) {
        this(executionId, eventConsumer, "model-request", null, null, null);
    }

    /**
     * 创建可采集请求 Token 构成的活动协调器。
     *
     * @param executionId 当前 Runtime 执行标识
     * @param eventConsumer Runtime 事件输出目标
     * @param requestIdPrefix 请求标识前缀，用于避免不同模型客户端的请求标识冲突
     * @param tokenEstimator 本地 Token 估算器
     * @param taskInstructions 任务和场景指令
     * @param mcpInstructions MCP 使用指令
     */
    public LangChainActivityEventCoordinator(String executionId,
                                             Consumer<RuntimeEvent> eventConsumer,
                                             String requestIdPrefix,
                                             LangChainTokenCountEstimator tokenEstimator,
                                             String taskInstructions,
                                             String mcpInstructions) {
        this(executionId, eventConsumer, requestIdPrefix, tokenEstimator,
                taskInstructions, mcpInstructions, null, true);
    }

    /**
     * 创建可关闭活动状态事件的请求指标协调器，供后台压缩模型复用。
     *
     * @param executionId 当前 Runtime 执行标识
     * @param eventConsumer Runtime 事件输出目标
     * @param requestIdPrefix 请求标识前缀
     * @param tokenEstimator 本地 Token 估算器
     * @param taskInstructions 任务和场景指令
     * @param mcpInstructions MCP 使用指令
     * @param requestSnapshotRoot 本地请求内容快照目录，为空时不写快照
     * @param activityEventsEnabled 是否输出面向任务活动流的瞬时状态
     */
    public LangChainActivityEventCoordinator(String executionId,
                                             Consumer<RuntimeEvent> eventConsumer,
                                             String requestIdPrefix,
                                             LangChainTokenCountEstimator tokenEstimator,
                                             String taskInstructions,
                                             String mcpInstructions,
                                             Path requestSnapshotRoot,
                                             boolean activityEventsEnabled) {
        this(executionId, eventConsumer, requestIdPrefix,
                tokenEstimator == null ? null : new LangChainContextBudget(
                        tokenEstimator, Integer.MAX_VALUE, 1D, taskInstructions, mcpInstructions),
                taskInstructions, mcpInstructions, requestSnapshotRoot, activityEventsEnabled);
    }

    /**
     * 创建使用执行级统一上下文预算的活动协调器。
     *
     * @param executionId 当前 Runtime 执行标识
     * @param eventConsumer Runtime 事件输出目标
     * @param requestIdPrefix 请求标识前缀
     * @param contextBudget 与 Memory 和模型请求边界共享的上下文预算
     * @param taskInstructions 任务和场景指令
     * @param mcpInstructions MCP 使用指令
     * @param requestSnapshotRoot 本地请求内容快照目录，为空时不写快照
     * @param activityEventsEnabled 是否输出面向任务活动流的瞬时状态
     */
    public LangChainActivityEventCoordinator(String executionId,
                                             Consumer<RuntimeEvent> eventConsumer,
                                             String requestIdPrefix,
                                             LangChainContextBudget contextBudget,
                                             String taskInstructions,
                                             String mcpInstructions,
                                             Path requestSnapshotRoot,
                                             boolean activityEventsEnabled) {
        this.executionId = executionId == null || executionId.isBlank() ? "langchain" : executionId;
        this.eventConsumer = eventConsumer;
        this.requestIdPrefix = requestIdPrefix == null || requestIdPrefix.isBlank()
                ? "model-request" : requestIdPrefix;
        this.contextBudget = contextBudget;
        this.requestSnapshotWriter = requestSnapshotRoot == null ? null
                : new LangChainRequestContentSnapshotWriter(requestSnapshotRoot, taskInstructions, mcpInstructions);
        this.activityEventsEnabled = activityEventsEnabled;
    }

    /**
     * 标记 Runtime 正在创建工具和模型客户端。
     *
     * @return 无返回值
     */
    public synchronized void preparing() {
        emitRunningState("langchain.runtime.preparing", "准备运行环境");
    }

    /**
     * 标记工具、模型、记忆和回调均已组装完成，随后可以发起首轮模型请求。
     *
     * @return 无返回值
     */
    public synchronized void prepared() {
        currentTransientState = null;
        eventConsumer.accept(new RuntimeEvent(
                RuntimeEventType.SYSTEM,
                RuntimeEventStatus.SUCCESS,
                "langchain.runtime.preparing",
                null,
                new RuntimeEventPayload(
                        "langchain.runtime.preparing", "runtime_state", "langchain.runtime.preparing",
                        "completed", null, null, null, null, null, null, null,
                        "langchain.runtime.preparing", "langchain.runtime.preparing", "准备运行环境", null, false
                )
        ));
    }

    /**
     * 在 LangChain4j 真正发出每轮模型请求时进入模型等待状态。
     *
     * @param requestContext 当前模型请求上下文
     * @return 无返回值
     */
    @Override
    public synchronized void onRequest(ChatModelRequestContext requestContext) {
        modelRequestRunning = true;
        currentModelRequestId = requestIdPrefix + "-" + (++modelRequestSequence);
        currentToolBatchId = null;
        currentToolBatchSize = 0;
        firstModelResponseReceived = false;
        dev.langchain4j.model.chat.request.ChatRequest request =
                requestContext == null ? null : requestContext.chatRequest();
        Map<String, String> metrics = requestMetrics(request);
        writeRequestSnapshot(request, metrics);
        emitModelRequestMetric("runtime.model.request.started", RuntimeEventSemantic.MODEL_REQUEST_STARTED,
                RuntimeEventStatus.RUNNING, metrics);
        emitThinking();
    }

    /**
     * 在模型响应结束时进入响应处理或工具调用处理状态。
     *
     * @param responseContext 当前模型响应上下文
     * @return 无返回值
     */
    @Override
    public synchronized void onResponse(ChatModelResponseContext responseContext) {
        String completedModelRequestId = currentModelRequestId;
        markFirstModelResponse();
        emitModelRequestMetric("runtime.model.request.completed", RuntimeEventSemantic.MODEL_REQUEST_COMPLETED,
                RuntimeEventStatus.SUCCESS,
                responseMetrics(responseContext));
        modelRequestRunning = false;
        currentModelRequestId = null;
        boolean hasToolRequests = responseContext != null
                && responseContext.chatResponse() != null
                && responseContext.chatResponse().aiMessage() != null
                && responseContext.chatResponse().aiMessage().hasToolExecutionRequests();
        if (hasToolRequests && completedModelRequestId != null) {
            currentToolBatchId = executionId + ":" + completedModelRequestId;
            currentToolBatchSize = responseContext.chatResponse().aiMessage().toolExecutionRequests().size();
        }
        if (hasRunningTools()) {
            return;
        }
        if (hasToolRequests) {
            deferToolEventsUntilIntermediateResponse = true;
            emitRunningState("langchain.tool-calls.processing", "处理工具调用");
            return;
        }
        emitRunningState("langchain.response.processing", "处理模型响应");
    }

    /**
     * 在模型请求失败后标记 Runtime 正在传递并处理异常。
     *
     * @param errorContext 当前模型错误上下文
     * @return 无返回值
     */
    @Override
    public synchronized void onError(ChatModelErrorContext errorContext) {
        emitModelRequestMetric("runtime.model.request.failed", RuntimeEventSemantic.MODEL_REQUEST_FAILED,
                RuntimeEventStatus.FAILED,
                errorMetrics(errorContext));
        modelRequestRunning = false;
        currentModelRequestId = null;
        emitRunningState("langchain.error.processing", "处理运行异常");
    }

    /**
     * 标记已经收到模型回答文本增量。
     *
     * @return 无返回值
     */
    public synchronized void modelTextReceived() {
        markFirstModelResponse();
        emitRunningState("langchain.response.generating", "生成回答");
    }

    /**
     * 标记已经收到模型工具调用增量。
     *
     * @return 无返回值
     */
    public synchronized void modelToolCallReceived() {
        markFirstModelResponse();
        deferToolEventsUntilIntermediateResponse = true;
        emitRunningState("langchain.tool-call.generating", "生成工具调用");
    }

    /**
     * 登记一个已经真实开始执行的工具并输出其开始事件。
     *
     * @param event 工具开始事件
     * @return 无返回值
     */
    public synchronized void toolStarted(RuntimeEvent event) {
        if (executionTerminated) {
            return;
        }
        String toolId = activityId(event);
        if (toolId == null) {
            anonymousRunningTools.add(event);
        } else {
            runningTools.put(toolId, event);
        }
        if (deferToolEventsUntilIntermediateResponse) {
            deferredToolEvents.add(event);
        } else {
            emitPersistent(event);
        }
    }

    /**
     * 结束对应工具；并行工具全部结束后进入真实的结果处理阶段。
     *
     * @param event 工具完成或失败事件
     * @return 无返回值
     */
    public synchronized void toolCompleted(RuntimeEvent event) {
        if (executionTerminated) {
            return;
        }
        String toolId = activityId(event);
        if (toolId == null) {
            if (!anonymousRunningTools.isEmpty()) {
                anonymousRunningTools.remove(0);
            }
        } else {
            runningTools.remove(toolId);
        }
        if (deferToolEventsUntilIntermediateResponse) {
            deferredToolEvents.add(event);
            return;
        }
        emitPersistent(event);
        if (hasRunningTools()) {
            return;
        }
        if (modelRequestRunning) {
            emitRunningState("langchain.tool-call.generating", "生成工具调用");
            return;
        }
        emitRunningState("langchain.tool-results.processing", "处理工具结果");
    }

    /**
     * 标记 LangChain4j 已处理完本轮中间模型响应，继续调度或汇总工具调用。
     *
     * @param messageEmitted 本轮是否已经输出持久化 Agent 消息
     * @return 无返回值
     */
    public synchronized void intermediateResponseHandled(boolean messageEmitted) {
        if (messageEmitted) {
            currentTransientState = null;
        }
        deferToolEventsUntilIntermediateResponse = false;
        deferredToolEvents.forEach(this::emitPersistent);
        deferredToolEvents.clear();
        if (hasRunningTools()) {
            return;
        }
        if (!modelRequestRunning) {
            emitRunningState("langchain.tool-results.processing", "处理工具结果");
        } else {
            emitRunningState("langchain.tool-calls.processing", "处理工具调用");
        }
    }

    /**
     * 标记最终响应已经收到，Runtime 正在校验并组装执行结果。
     *
     * @param messageEmitted 最终回答是否已经输出持久化 Agent 消息
     * @return 无返回值
     */
    public synchronized void finalResponseHandled(boolean messageEmitted) {
        if (messageEmitted) {
            currentTransientState = null;
        }
        modelRequestRunning = false;
        deferToolEventsUntilIntermediateResponse = false;
        deferredToolEvents.forEach(this::emitPersistent);
        deferredToolEvents.clear();
        runningTools.clear();
        anonymousRunningTools.clear();
        emitRunningState("langchain.result.finalizing", "整理结果");
    }

    /**
     * 标记异步执行错误已经返回，Runtime 正在结束本次执行。
     *
     * @return 无返回值
     */
    public synchronized void executionFailed() {
        terminateExecution("任务执行失败，操作未完成");
    }

    /**
     * 关闭被用户取消时仍在执行的工具事件，避免历史记录残留未配对的 RUNNING 动作。
     *
     * @return 无返回值
     */
    public synchronized void executionCancelled() {
        terminateExecution("任务已取消，操作未完成");
    }

    private void terminateExecution(String reason) {
        if (executionTerminated) {
            return;
        }
        executionTerminated = true;
        modelRequestRunning = false;
        deferToolEventsUntilIntermediateResponse = false;
        deferredToolEvents.forEach(this::emitPersistent);
        deferredToolEvents.clear();
        runningTools.values().forEach(event -> emitPersistent(failedToolEvent(event, reason)));
        anonymousRunningTools.forEach(event -> emitPersistent(failedToolEvent(event, reason)));
        runningTools.clear();
        anonymousRunningTools.clear();
        emitRunningState("langchain.error.processing", "处理运行异常");
    }

    private RuntimeEvent failedToolEvent(RuntimeEvent runningEvent, String reason) {
        RuntimeEventPayload payload = runningEvent == null ? null : runningEvent.payload();
        return new RuntimeEvent(
                runningEvent == null ? RuntimeEventType.COMMAND : runningEvent.type(),
                RuntimeEventStatus.FAILED,
                runningEvent == null ? "langchain.tool" : runningEvent.title(),
                reason,
                payload == null ? null : payload.withStatusAndOutput("failed", reason));
    }

    private void emitPersistent(RuntimeEvent event) {
        currentTransientState = null;
        RuntimeEventPayload payload = event == null ? null : event.payload();
        if (payload != null && currentToolBatchId != null) {
            eventConsumer.accept(new RuntimeEvent(
                    event.type(), event.status(), event.title(), event.detail(),
                    payload.withActionGroup(currentToolBatchId, currentToolBatchSize)));
            return;
        }
        eventConsumer.accept(event);
    }

    private void emitThinking() {
        if (!activityEventsEnabled) {
            return;
        }
        if (THINKING_STATE.equals(currentTransientState)) {
            return;
        }
        currentTransientState = THINKING_STATE;
        eventConsumer.accept(new RuntimeEvent(
                RuntimeEventType.THINKING,
                RuntimeEventStatus.RUNNING,
                THINKING_STATE,
                null,
                transientPayload(THINKING_STATE, null)
        ));
    }

    private void markFirstModelResponse() {
        if (!modelRequestRunning || currentModelRequestId == null || firstModelResponseReceived) {
            return;
        }
        firstModelResponseReceived = true;
        emitModelRequestMetric("runtime.model.request.first-response",
                RuntimeEventSemantic.MODEL_REQUEST_FIRST_RESPONSE, RuntimeEventStatus.INFO, Map.of());
    }

    private void emitModelRequestMetric(String rawType, RuntimeEventSemantic semantic,
                                        RuntimeEventStatus status, Map<String, String> metrics) {
        if (currentModelRequestId == null) {
            return;
        }
        eventConsumer.accept(new RuntimeEvent(
                RuntimeEventType.METRIC,
                status,
                rawType,
                null,
                new RuntimeEventPayload(
                        rawType, "model_request", currentModelRequestId, status.name().toLowerCase(),
                        null, null, null, null, null, null, null,
                        "runtime:model-request", currentModelRequestId, "模型 API 请求", "MEASURED", false,
                        metrics
                ).withSemantic(semantic).withModelTimingMode(RuntimeModelTimingMode.STREAMING)
        ));
    }

    private Map<String, String> requestMetrics(dev.langchain4j.model.chat.request.ChatRequest request) {
        Map<String, String> metrics = new LinkedHashMap<>();
        if (request == null) {
            return metrics;
        }
        put(metrics, "model", request.modelName());
        put(metrics, "messageCount", request.messages() == null ? null : request.messages().size());
        put(metrics, "toolDefinitionCount", request.toolSpecifications() == null ? null : request.toolSpecifications().size());
        if (contextBudget != null) {
            LangChainContextBudget.Snapshot breakdown = contextBudget.estimate(request);
            put(metrics, "estimatedInputTokens", breakdown.estimatedInputTokens());
            put(metrics, "systemInstructionTokens", breakdown.systemInstructionTokens());
            put(metrics, "taskInstructionTokens", breakdown.taskInstructionTokens());
            put(metrics, "mcpInstructionTokens", breakdown.mcpInstructionTokens());
            put(metrics, "toolSchemaTokens", breakdown.toolSchemaTokens());
            put(metrics, "conversationTokens", breakdown.conversationTokens());
            put(metrics, "toolResultTokens", breakdown.toolResultTokens());
            put(metrics, "imageTokens", breakdown.imageTokens());
        }
        return metrics;
    }

    private void writeRequestSnapshot(dev.langchain4j.model.chat.request.ChatRequest request,
                                      Map<String, String> metrics) {
        if (requestSnapshotWriter == null || request == null) {
            return;
        }
        try {
            Path snapshot = requestSnapshotWriter.write(currentModelRequestId, request, metrics);
            log.info("Model request input snapshot written executionId={} requestId={} path={}",
                    executionId, currentModelRequestId, snapshot);
        } catch (Exception exception) {
            log.warn("Model request input snapshot failed executionId={} requestId={} message={}",
                    executionId, currentModelRequestId, exception.getMessage());
        }
    }

    private Map<String, String> responseMetrics(ChatModelResponseContext context) {
        Map<String, String> metrics = new LinkedHashMap<>(requestMetrics(context == null ? null : context.chatRequest()));
        if (context == null || context.chatResponse() == null) {
            return metrics;
        }
        TokenUsage usage = context.chatResponse().tokenUsage();
        put(metrics, "model", context.chatResponse().modelName());
        put(metrics, "inputTokens", usage == null ? null : usage.inputTokenCount());
        put(metrics, "outputTokens", usage == null ? null : usage.outputTokenCount());
        put(metrics, "totalTokens", usage == null ? null : usage.totalTokenCount());
        if (usage instanceof OpenAiTokenUsage openAiUsage) {
            put(metrics, "cachedInputTokens", openAiUsage.inputTokensDetails() == null
                    ? null : openAiUsage.inputTokensDetails().cachedTokens());
            put(metrics, "reasoningOutputTokens", openAiUsage.outputTokensDetails() == null
                    ? null : openAiUsage.outputTokensDetails().reasoningTokens());
        }
        boolean toolCall = context.chatResponse().aiMessage() != null
                && context.chatResponse().aiMessage().hasToolExecutionRequests();
        put(metrics, "responseKind", toolCall ? "TOOL_CALL" : "ANSWER");
        put(metrics, "toolRequestCount", toolCall
                ? context.chatResponse().aiMessage().toolExecutionRequests().size() : 0);
        return metrics;
    }

    private Map<String, String> errorMetrics(ChatModelErrorContext context) {
        Map<String, String> metrics = new LinkedHashMap<>(requestMetrics(context == null ? null : context.chatRequest()));
        Throwable error = context == null ? null : context.error();
        put(metrics, "errorType", error == null ? null : "MODEL_REQUEST_ERROR");
        String errorMessage = error == null ? null : LangChainErrorMessageKit.userMessage(error);
        put(metrics, "errorMessage", errorMessage == null
                ? null : errorMessage.substring(0, Math.min(500, errorMessage.length())));
        return metrics;
    }

    private void put(Map<String, String> target, String key, Object value) {
        if (value != null) {
            target.put(key, String.valueOf(value));
        }
    }

    private void emitRunningState(String stateKey, String label) {
        if (!activityEventsEnabled) {
            return;
        }
        if (stateKey.equals(currentTransientState)) {
            return;
        }
        currentTransientState = stateKey;
        eventConsumer.accept(new RuntimeEvent(
                RuntimeEventType.SYSTEM,
                RuntimeEventStatus.RUNNING,
                stateKey,
                null,
                transientPayload(stateKey, label)
        ));
    }

    private RuntimeEventPayload transientPayload(String stateKey, String label) {
        return new RuntimeEventPayload(
                stateKey, "runtime_state", stateKey, "running", null, null, null, null,
                null, null, null, stateKey, stateKey, label, null, true
        );
    }

    private boolean hasRunningTools() {
        return !anonymousRunningTools.isEmpty() || !runningTools.isEmpty();
    }

    private String activityId(RuntimeEvent event) {
        RuntimeEventPayload payload = event == null ? null : event.payload();
        if (payload == null) {
            return null;
        }
        if (payload.actionInstanceId() != null && !payload.actionInstanceId().isBlank()) {
            return payload.actionInstanceId();
        }
        if (payload.callId() != null && !payload.callId().isBlank()) {
            return payload.callId();
        }
        return null;
    }
}
