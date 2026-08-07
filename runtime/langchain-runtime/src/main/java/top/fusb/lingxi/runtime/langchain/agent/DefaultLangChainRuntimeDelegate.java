package top.fusb.lingxi.runtime.langchain.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.runtime.api.event.RuntimeActionIcon;
import top.fusb.lingxi.runtime.api.event.RuntimeEvent;
import top.fusb.lingxi.runtime.api.event.RuntimeEventListener;
import top.fusb.lingxi.runtime.api.event.RuntimeEventPayload;
import top.fusb.lingxi.runtime.api.event.RuntimeEventSemantic;
import top.fusb.lingxi.runtime.api.event.RuntimeEventStatus;
import top.fusb.lingxi.runtime.api.event.RuntimeEventType;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionRequest;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionResult;
import top.fusb.lingxi.runtime.api.model.RuntimeModelConfig;
import top.fusb.lingxi.runtime.api.model.RuntimeModelProtocol;
import top.fusb.lingxi.runtime.api.model.RuntimeMessageDelta;
import top.fusb.lingxi.runtime.api.model.RuntimeMessageDeltaType;
import top.fusb.lingxi.runtime.api.model.RuntimeSessionRef;
import top.fusb.lingxi.runtime.api.model.RuntimeUsage;
import top.fusb.lingxi.runtime.api.model.RuntimeUsageQuery;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpServerConfig;
import top.fusb.lingxi.runtime.langchain.agent.activity.LangChainActivityEventCoordinator;
import top.fusb.lingxi.runtime.langchain.agent.evidence.LangChainEvidenceStore;
import top.fusb.lingxi.runtime.langchain.agent.evidence.LangChainEvidenceTools;
import top.fusb.lingxi.runtime.langchain.agent.memory.LangChainActiveToolResultProjector;
import top.fusb.lingxi.runtime.langchain.agent.memory.LangChainCompactingChatMemory;
import top.fusb.lingxi.runtime.langchain.agent.memory.LangChainCompactionStateStore;
import top.fusb.lingxi.runtime.langchain.agent.memory.LangChainFileChatMemoryStore;
import top.fusb.lingxi.runtime.langchain.agent.memory.LangChainModelConversationCompactor;
import top.fusb.lingxi.runtime.langchain.agent.memory.LangChainTokenCountEstimator;
import top.fusb.lingxi.runtime.langchain.agent.model.LangChainChatCompletionsMultimodalAdapter;
import top.fusb.lingxi.runtime.langchain.agent.model.LangChainChatThinkingProtocol;
import top.fusb.lingxi.runtime.langchain.agent.model.LangChainContextBudget;
import top.fusb.lingxi.runtime.langchain.agent.model.LangChainGuardedChatModel;
import top.fusb.lingxi.runtime.langchain.agent.model.LangChainGuardedStreamingChatModel;
import top.fusb.lingxi.runtime.langchain.agent.model.LangChainModelInputNormalizer;
import top.fusb.lingxi.runtime.langchain.agent.model.LangChainModelCallPolicy;
import top.fusb.lingxi.runtime.langchain.agent.model.LangChainRetryingChatModel;
import top.fusb.lingxi.runtime.langchain.agent.model.LangChainRetryingStreamingChatModel;
import top.fusb.lingxi.runtime.langchain.agent.tool.LangChainToolExecutors;
import top.fusb.lingxi.runtime.langchain.capability.LangChainCapabilityRegistry;
import top.fusb.lingxi.runtime.langchain.capability.LangChainRegistryToolProvider;
import top.fusb.lingxi.runtime.langchain.capability.LangChainWorkspaceTools;
import top.fusb.lingxi.runtime.langchain.config.LangChainRuntimeProperties;
import top.fusb.lingxi.runtime.langchain.core.LangChainRuntimeDelegate;
import top.fusb.lingxi.runtime.langchain.file.LangChainFileTools;
import top.fusb.lingxi.runtime.langchain.file.LangChainManagedFileRegistry;
import top.fusb.lingxi.runtime.langchain.mcp.LangChainMcpRuntime;
import top.fusb.lingxi.runtime.langchain.mcp.LangChainMcpTools;
import top.fusb.lingxi.runtime.langchain.util.LangChainErrorMessageKit;
import top.fusb.lingxi.runtime.langchain.util.LangChainPromptKit;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class DefaultLangChainRuntimeDelegate implements LangChainRuntimeDelegate, AutoCloseable {

    private static final String FILE_ACCESS_FEATURE = "file-access";
    private final LangChainRuntimeProperties properties;
    private final boolean requestInputSnapshotsEnabled;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<RuntimeMcpServerConfig, LangChainMcpRuntime> mcpRuntimes = new ConcurrentHashMap<>();
    private final ExecutorService toolExecutor;
    private final Map<String, ExecutionHandle> executions = new ConcurrentHashMap<>();
    private final Map<String, RuntimeSessionRef> sessions = new ConcurrentHashMap<>();
    private final Map<String, RuntimeUsage> usageByExecution = new ConcurrentHashMap<>();
    private final String systemPrompt;

    public DefaultLangChainRuntimeDelegate(LangChainRuntimeProperties properties) {
        this(properties, properties.getDebug().isRequestInputSnapshotsEnabled());
    }

    public DefaultLangChainRuntimeDelegate(LangChainRuntimeProperties properties,
                                           boolean requestInputSnapshotsEnabled) {
        if (properties.getToolConcurrency() < 1) {
            throw new IllegalArgumentException("模型工具并发数必须大于 0");
        }
        if (properties.getMaxMemoryTokens() < 1_000) {
            throw new IllegalArgumentException("模型上下文 Token 预算不能小于 1000");
        }
        if (properties.getReservedOutputTokens() < 1_000
                || properties.getReservedOutputTokens() >= properties.getMaxMemoryTokens()) {
            throw new IllegalArgumentException("模型输出预留 Token 必须大于 1000 且小于上下文窗口");
        }
        if (properties.getCompactionTriggerRatio() <= 0D || properties.getCompactionTriggerRatio() > 1D
                || properties.getCompactionRecentTokens() < 1_000
                || properties.getCompactionRecentMinimumTokens() < 1_000
                || properties.getCompactionRecentMinimumTokens() > properties.getCompactionRecentTokens()
                || properties.getCompactionRecentTurns() < 1
                || properties.getCompactionMinimumTokens() < 1_000
                || properties.getCompactionMaxOutputTokens() < 1_000
                || properties.getRetainedToolOutputTokens() < 1_000
                || properties.getToolOutputPruneMinimumTokens() < 1_000) {
            throw new IllegalArgumentException("模型上下文压缩参数无效");
        }
        if (properties.getImageTokenEstimate() < 1) {
            throw new IllegalArgumentException("模型图片 Token 估算值必须大于 0");
        }
        if (properties.getToolOutputPreviewChars() < 0) {
            throw new IllegalArgumentException("模型工具输出预览字符数不能小于 0");
        }
        if (properties.getActiveToolResultMaxTokens() < 1
                || properties.getTimeFinalizationGraceSeconds() < 0L) {
            throw new IllegalArgumentException("活动工具结果裁剪和时间收尾参数无效");
        }
        if (properties.getMaxToolCallsPerExecution() < 1
                || properties.getMaxEvidenceReadsPerExecution() < 1
                || properties.getMaxRepeatedCapabilityCalls() < 1
                || properties.getMaxModelTokensPerExecution() < 0
                || properties.getModelTokenFinalizationReserve() < 0
                || properties.getMaxModelTokensPerExecution() > 0
                && (properties.getModelTokenFinalizationReserve() < 1
                || properties.getModelTokenFinalizationReserve() >= properties.getMaxModelTokensPerExecution())) {
            throw new IllegalArgumentException("单次执行预算参数无效；累计 Token 上限为 0 时表示不限制");
        }
        this.properties = properties;
        this.requestInputSnapshotsEnabled = requestInputSnapshotsEnabled;
        AtomicInteger threadIndex = new AtomicInteger();
        int concurrency = properties.getToolConcurrency();
        this.toolExecutor = new ThreadPoolExecutor(
                concurrency,
                concurrency,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(64, concurrency * 8)),
                runnable -> {
                    Thread thread = new Thread(runnable,
                            "lingxi-langchain-tool-" + threadIndex.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        this.systemPrompt = LangChainPromptKit.load("langchain-agent-system.md");
    }

    /**
     * 使用 LangChain4j 执行一次支持能力 CLI 和可选代码 MCP 的 Agent 任务。
     *
     * @param request Runtime 统一执行请求
     * @param listener Runtime 事件与用量监听器
     * @return 执行结果、最终回答、会话引用和 Token 用量
     * @throws IllegalArgumentException 请求、模型配置或工作区无效时抛出
     * @throws IllegalStateException 模型调用失败、执行超时或 Agent 未返回结果时抛出
     */
    @Override
    public RuntimeExecutionResult execute(RuntimeExecutionRequest request, RuntimeEventListener listener) {
        validateRequest(request);
        long timeoutSeconds = request.timeoutSeconds() > 0
                ? request.timeoutSeconds() : properties.getDefaultTimeoutSeconds();
        long executionDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        Path workspace;
        try {
            workspace = Path.of(request.workspace().executionRoot()).toAbsolutePath().normalize().toRealPath();
        } catch (Exception exception) {
            throw new IllegalArgumentException("处理目录无法访问：" + request.workspace().executionRoot(), exception);
        }
        int modelContextWindow = modelContextWindow(request.modelConfig());
        int outputReserve = Math.min(properties.getReservedOutputTokens(),
                Math.max(4_000, modelContextWindow / 4));
        int inputTokenBudget = modelContextWindow - outputReserve;
        int minimumCompactionTokens = Math.min(properties.getCompactionMinimumTokens(),
                Math.max(1_000, inputTokenBudget / 3));
        long finalizationReserve = 0L;
        if (properties.getMaxModelTokensPerExecution() > 0L) {
            long requestsBeforeFinalDelivery = request.finalResponseRequired() ? 2L : 1L;
            long minimumFinalizationReserve = requestsBeforeFinalDelivery * modelContextWindow + 1L;
            finalizationReserve = Math.max(
                    properties.getModelTokenFinalizationReserve(), minimumFinalizationReserve);
            if (finalizationReserve >= properties.getMaxModelTokensPerExecution()) {
                throw new IllegalArgumentException("单次执行 Token 总预算不足以完成模型收尾，请调大总预算或调小模型上下文窗口");
            }
        }
        LangChainExecutionContext context = new LangChainExecutionContext(
                request.executionId(), request.workspace(), listener, request.environment(), (long) modelContextWindow,
                properties.getMaxToolCallsPerExecution(),
                properties.getMaxEvidenceReadsPerExecution(),
                properties.getMaxRepeatedCapabilityCalls(),
                properties.getMaxModelTokensPerExecution(),
                finalizationReserve);
        LangChainTokenCountEstimator tokenEstimator = new LangChainTokenCountEstimator(
                request.modelConfig().model(), properties.getImageTokenEstimate());
        LangChainContextBudget contextBudget = new LangChainContextBudget(
                tokenEstimator, inputTokenBudget, properties.getCompactionTriggerRatio(),
                request.taskInstructions(), request.mcpInstructions());
        LangChainModelCallPolicy modelCallPolicy = new LangChainModelCallPolicy(
                properties.getModelMaxAttempts(), properties.getModelRetryInitialBackoff(),
                properties.getModelRetryMaximumBackoff());
        Path requestSnapshotRoot = requestInputSnapshotsEnabled
                ? Path.of(request.workspace().taskContextRoot()).resolve("debug/langchain/request-inputs") : null;
        LangChainActivityEventCoordinator activityCoordinator = new LangChainActivityEventCoordinator(
                request.eventNamespace(), context::emit, "model-request", contextBudget,
                request.taskInstructions(), request.mcpInstructions(), requestSnapshotRoot, true);
        LangChainActivityEventCoordinator compactionActivityCoordinator = new LangChainActivityEventCoordinator(
                request.eventNamespace(), context::emit, "compaction-request", tokenEstimator,
                null, null, requestSnapshotRoot, false);
        activityCoordinator.preparing();
        CountDownLatch completed = new CountDownLatch(1);
        ExecutionHandle handle = new ExecutionHandle(context, completed, activityCoordinator);
        if (executions.putIfAbsent(request.executionId(), handle) != null) {
            throw new IllegalStateException("任务正在执行，请勿重复提交：" + request.executionId());
        }

        AtomicReference<ChatResponse> finalResponse = new AtomicReference<>();
        AtomicReference<Throwable> executionError = new AtomicReference<>();
        StringBuffer stdout = new StringBuffer();
        StringBuffer roundText = new StringBuffer();
        StringBuffer roundThinking = new StringBuffer();
        AtomicInteger modelRound = new AtomicInteger(1);
        LangChainCapabilityRegistry capabilityRegistry = new LangChainCapabilityRegistry(request.environment());
        Path memoryFile = Path.of(request.workspace().runtimeStateRoot()).resolve("chat-memory.json");
        Path compactionStateFile = LangChainCompactionStateStore.stateFileForMemory(memoryFile);
        LangChainFileChatMemoryStore chatMemoryStore = new LangChainFileChatMemoryStore(memoryFile);
        LangChainEvidenceStore evidenceStore = new LangChainEvidenceStore(
                Path.of(request.workspace().runtimeStateRoot()).resolve("evidence"), objectMapper);
        LangChainCompactionStateStore compactionStateStore =
                new LangChainCompactionStateStore(compactionStateFile, objectMapper);
        if (request.recovering()) {
            restoreSessionIfCurrentMemoryMissing(request.resumeSession(), chatMemoryStore);
            restoreEvidenceIfCurrentIndexMissing(request.resumeSession(), evidenceStore);
            int interruptedTools = chatMemoryStore.completeInterruptedToolCalls(request.executionId());
            log.info("LangChain execution context restored executionId={} memoryFile={} interruptedTools={}",
                    request.executionId(), memoryFile, interruptedTools);
        } else if (request.resumeSession() != null) {
            Path sourceMemoryFile = sessionPath(request.resumeSession());
            int restoredMessages = chatMemoryStore.restoreFrom(sourceMemoryFile);
            evidenceStore.restoreFrom(LangChainEvidenceStore.indexFileForMemory(sourceMemoryFile));
            if (!sourceMemoryFile.equals(memoryFile.toAbsolutePath().normalize())) {
                compactionStateStore.restoreFrom(
                        LangChainCompactionStateStore.stateFileForMemory(sourceMemoryFile));
            }
            log.info("LangChain conversation resumed executionId={} sessionId={} restoredMessages={}",
                    request.executionId(), request.resumeSession().sessionId(), restoredMessages);
        } else {
            chatMemoryStore.deleteMessages(request.executionId());
            compactionStateStore.clear();
            evidenceStore.clear();
        }
        LangChainActiveToolResultProjector activeToolResultProjector = new LangChainActiveToolResultProjector(
                request.executionId(), tokenEstimator, evidenceStore,
                properties.getActiveToolResultMaxTokens());
        ChatModel compactionModel = new LangChainRetryingChatModel(
                new LangChainGuardedChatModel(compactionModel(
                        request.modelConfig(), request.timeoutSeconds(), compactionActivityCoordinator), context),
                modelCallPolicy);
        LangChainCompactingChatMemory chatMemory = new LangChainCompactingChatMemory(
                request.executionId(),
                chatMemoryStore,
                compactionStateStore,
                contextBudget,
                new LangChainModelConversationCompactor(compactionModel),
                properties.getCompactionRecentTokens(),
                properties.getCompactionRecentMinimumTokens(),
                properties.getCompactionRecentTurns(),
                minimumCompactionTokens,
                properties.getRetainedToolOutputTokens(),
                properties.getToolOutputPruneMinimumTokens(),
                () -> context.emit(new RuntimeEvent(
                        RuntimeEventType.METRIC,
                        RuntimeEventStatus.INFO,
                        "langchain.context.compacted",
                        null,
                        new RuntimeEventPayload(
                                "langchain.context.compacted", "context_compaction", null, "completed",
                                null, null, null, null, null, null, null,
                                null, null, null, null, false)
                                .withSemantic(RuntimeEventSemantic.CONTEXT_COMPACTION))),
                activeToolResultProjector
        );
        boolean fileToolsEnabled = request.workspaceFileToolsEnabled();
        List<LangChainMcpRuntime> executionMcpRuntimes = request.mcpServers().stream()
                .map(this::mcpRuntime)
                .toList();
        try {
            LangChainManagedFileRegistry fileRegistry = new LangChainManagedFileRegistry(properties, context);
            LangChainWorkspaceTools workspaceTools = new LangChainWorkspaceTools(
                    properties,
                    context,
                    objectMapper,
                    capabilityRegistry,
                    evidenceStore,
                    output -> {
                        com.fasterxml.jackson.databind.node.ObjectNode resource = objectMapper.createObjectNode()
                                .put("type", output.type())
                                .put("location", output.location());
                        output.features().forEach(resource.putArray("features")::add);
                        if (output.features().contains(FILE_ACCESS_FEATURE)) {
                            Path fileRoot = fileRegistry.registerExternalRoot(output.location(), false);
                            resource.put("fileRoot", fileRoot.toString());
                        }
                        return resource;
                    });
            List<Object> tools = new ArrayList<>();
            tools.add(workspaceTools);
            tools.add(new LangChainEvidenceTools(
                    properties, context, evidenceStore, objectMapper, tokenEstimator));
            if (fileToolsEnabled) {
                tools.add(new LangChainFileTools(
                        properties, context, fileRegistry, evidenceStore, objectMapper,
                        request.modelConfig().imageInputSupported()));
            }
            if (!executionMcpRuntimes.isEmpty()) {
                tools.add(new LangChainMcpTools(
                        request.mcpServers(), executionMcpRuntimes, context, evidenceStore));
            }
            List<ToolProvider> toolProviders = new ArrayList<>();
            if (!capabilityRegistry.nativeCommands().isEmpty()
                    || !capabilityRegistry.skillCommands().isEmpty()) {
                toolProviders.add(new LangChainRegistryToolProvider(
                        capabilityRegistry, workspaceTools, objectMapper));
            }
            StreamingChatModel selectedModel = streamingModel(
                    request.modelConfig(), request.timeoutSeconds(), activityCoordinator);
            String agentSystemPrompt = systemPrompt(request.instructions(), request.modelConfig());
            if (request.modelConfig().imageInputSupported()
                    && protocol(request.modelConfig()) == RuntimeModelProtocol.CHAT_COMPLETIONS) {
                selectedModel = new LangChainChatCompletionsMultimodalAdapter(selectedModel);
            }
            StreamingChatModel model = new LangChainRetryingStreamingChatModel(
                    new LangChainGuardedStreamingChatModel(
                            selectedModel, context, contextBudget, activeToolResultProjector,
                            new LangChainModelInputNormalizer(
                                    request.modelConfig().imageInputSupported(), false)),
                    modelCallPolicy, context);
            var configuredToolExecutors = LangChainToolExecutors.create(tools, context);
            var initialToolSpecifications = new ArrayList<>(configuredToolExecutors.keySet());
            toolProviders.forEach(provider -> initialToolSpecifications.addAll(
                    provider.provideTools(null).tools().keySet()));
            contextBudget.reserveToolSpecifications(initialToolSpecifications);
            AiServices<LangChainAgent> builder = AiServices.builder(LangChainAgent.class)
                    .streamingChatModel(model)
                    .systemMessage(agentSystemPrompt)
                    .chatMemory(chatMemory)
                    .tools(configuredToolExecutors)
                    .executeToolsConcurrently(toolExecutor)
                    .hallucinatedToolNameStrategy(this::hallucinatedToolResult)
                    .toolArgumentsErrorHandler((error, ignored) -> ToolErrorHandlerResult.text(
                            "工具参数无效：" + errorMessage(error) + "。请按工具参数定义修正，不要重复提交相同参数。"))
                    .toolExecutionErrorHandler((error, ignored) -> ToolErrorHandlerResult.text(
                            "工具执行失败：" + errorMessage(error) + "。请根据错误信息修正一次，仍失败则说明限制。"));
            if (!toolProviders.isEmpty()) {
                builder.toolProviders(toolProviders);
            }
            LangChainAgent agent = builder.build();

            TokenStream stream = agent.chat(request.prompt());
            stream.onPartialResponse(delta -> {
                        activityCoordinator.modelTextReceived();
                        roundText.append(delta);
                        if (!request.finalResponseRequired()) {
                            context.emitMessageDelta(new RuntimeMessageDelta("round-" + modelRound.get(), delta));
                        }
                    })
                    .onPartialThinking(partial -> appendThinking(
                            context, roundThinking, partial, modelRound.get()))
                    .onPartialToolCall(ignored -> activityCoordinator.modelToolCallReceived())
                    .onIntermediateResponse(response -> {
                        emitModelReasoning(context, response, roundThinking, modelRound.get());
                        String progress = responseText(response, roundText);
                        boolean messageEmitted = !progress.isBlank();
                        if (!progress.isBlank()) {
                            appendLine(stdout, progress);
                            context.emit(agentMessage(progress, modelRound.get(), false));
                        }
                        activityCoordinator.intermediateResponseHandled(messageEmitted);
                        roundText.setLength(0);
                        roundThinking.setLength(0);
                        modelRound.incrementAndGet();
                    })
                    .beforeToolExecution(event -> activityCoordinator.toolStarted(toolEvent(
                            event.request().name(), event.request().id(), event.request().arguments(),
                            null, RuntimeEventStatus.RUNNING, capabilityRegistry, context)))
                    .onToolExecuted(event -> activityCoordinator.toolCompleted(
                            toolExecutedEvent(event, capabilityRegistry, context)))
                    .onCompleteResponse(response -> {
                        finalResponse.set(response);
                        emitModelReasoning(context, response, roundThinking, modelRound.get());
                        String answer = response.aiMessage() == null || response.aiMessage().text() == null
                                ? "" : response.aiMessage().text().strip();
                        if (!request.finalResponseRequired() && !answer.isBlank()) {
                            appendLine(stdout, answer);
                            context.emit(agentMessage(answer, modelRound.get(), true));
                        }
                        if (!request.finalResponseRequired()) {
                            activityCoordinator.finalResponseHandled(!answer.isBlank());
                        }
                        completed.countDown();
                    })
                    .onError(error -> {
                        activityCoordinator.executionFailed();
                        executionError.set(error);
                        completed.countDown();
                    });
            activityCoordinator.prepared();
            stream.start();

            long remainingNanos = executionDeadlineNanos - System.nanoTime();
            long finalizationGraceNanos = TimeUnit.SECONDS.toNanos(properties.getTimeFinalizationGraceSeconds());
            long explorationNanos = Math.max(0L, remainingNanos - finalizationGraceNanos);
            boolean finished = explorationNanos > 0L
                    && completed.await(explorationNanos, TimeUnit.NANOSECONDS);
            if (!finished && completed.getCount() > 0L) {
                context.enterTimeFinalization();
                remainingNanos = executionDeadlineNanos - System.nanoTime();
                finished = remainingNanos > 0L && completed.await(remainingNanos, TimeUnit.NANOSECONDS);
            }
            if (!finished && completed.getCount() > 0L) {
                activityCoordinator.executionFailed();
                context.cancel();
                throw new IllegalStateException("任务执行超时");
            }
            if (context.isCancelled()) {
                return cancelledResult(stdout.toString(), context);
            }
            if (executionError.get() != null) {
                throw new IllegalStateException(errorMessage(executionError.get()), executionError.get());
            }
            ChatResponse response = finalResponse.get();
            if (response == null) {
                throw new IllegalStateException("模型未返回最终响应");
            }

            String draft = requireValidFinalResponse(request.executionId(), response);
            String answer = draft;
            if (request.finalResponseRequired()) {
                int finalRound = modelRound.incrementAndGet();
                answer = finalizeResponse(request, draft, context, activityCoordinator, modelCallPolicy,
                        finalRound, executionDeadlineNanos);
                if (context.isCancelled()) {
                    return cancelledResult(stdout.toString(), context);
                }
                chatMemory.commitFinalResponse(answer);
                appendLine(stdout, answer);
                context.emit(agentMessage(answer, finalRound, true));
                activityCoordinator.finalResponseHandled(true);
            }
            RuntimeUsage usage = context.usage();
            RuntimeSessionRef session = session(request, chatMemoryStore.memoryFile());
            if (usage != null) {
                usageByExecution.put(request.executionId(), usage);
            }
            log.info("LangChain execution completed executionId={} model={} rounds={}",
                    request.executionId(), request.modelConfig().model(), modelRound.get());
            return new RuntimeExecutionResult(
                    0, stdout.toString(), "", answer, session, usage, LocalDateTime.now());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            activityCoordinator.executionFailed();
            context.cancel();
            throw new IllegalStateException("任务执行被中断", exception);
        } finally {
            RuntimeUsage finalUsage = context.usage();
            if (finalUsage != null) {
                usageByExecution.put(request.executionId(), finalUsage);
            }
            executions.remove(request.executionId());
            executionMcpRuntimes.forEach(runtime -> runtime.cleanupExecution(request.executionId()));
        }
    }

    /**
     * 将不存在的工具名转换为可恢复的工具错误，让模型依据当前工具定义修正后继续执行。
     *
     * @param request 模型生成的未知工具请求
     * @return 带错误标记且保留原调用 ID 的工具结果消息
     */
    ToolExecutionResultMessage hallucinatedToolResult(ToolExecutionRequest request) {
        String toolName = request == null || request.name() == null ? "" : request.name();
        return ToolExecutionResultMessage.builder()
                .id(request == null ? null : request.id())
                .toolName(toolName)
                .text("工具不存在：" + toolName + "。请从当前已提供的工具定义中选择名称和用途匹配的工具后重试。")
                .isError(true)
                .build();
    }

    /**
     * 取消指定 LangChain 执行并终止正在运行的能力 CLI 子进程。
     *
     * @param executionId 平台执行 ID
     * @return 找到运行中任务并成功发出取消信号时返回 true
     * @throws RuntimeException 取消过程不主动向外抛出异常
     */
    @Override
    public boolean cancel(String executionId) {
        ExecutionHandle handle = executions.get(executionId);
        if (handle == null) {
            return false;
        }
        handle.activityCoordinator().executionCancelled();
        boolean cancelled = handle.context().cancel();
        handle.completed().countDown();
        return cancelled;
    }

    @Override
    public long defaultTimeoutSeconds() {
        return properties.getDefaultTimeoutSeconds();
    }

    @Override
    public Optional<RuntimeSessionRef> latestSession(String conversationId) {
        return Optional.ofNullable(sessions.get(conversationId));
    }

    @Override
    public Optional<RuntimeUsage> readUsage(RuntimeUsageQuery query) {
        return query == null ? Optional.empty() : Optional.ofNullable(usageByExecution.get(query.executionId()));
    }

    @Override
    public void close() {
        executions.values().forEach(handle -> {
            handle.activityCoordinator().executionCancelled();
            handle.context().cancel();
        });
        executions.clear();
        toolExecutor.shutdownNow();
        mcpRuntimes.values().forEach(LangChainMcpRuntime::close);
        mcpRuntimes.clear();
        sessions.clear();
        usageByExecution.clear();
    }

    private LangChainMcpRuntime mcpRuntime(RuntimeMcpServerConfig server) {
        return mcpRuntimes.computeIfAbsent(server,
                config -> new LangChainMcpRuntime(properties, objectMapper, config));
    }

    StreamingChatModel streamingModel(RuntimeModelConfig config,
                                      long timeoutSeconds,
                                      LangChainActivityEventCoordinator activityCoordinator) {
        validateModelConfig(config);
        Duration timeout = Duration.ofSeconds(timeoutSeconds > 0
                ? timeoutSeconds : properties.getDefaultTimeoutSeconds());
        JdkHttpClientBuilder baseHttpClient = new JdkHttpClientBuilder()
                .connectTimeout(timeout)
                .readTimeout(timeout);
        if (protocol(config) == RuntimeModelProtocol.CHAT_COMPLETIONS) {
            dev.langchain4j.http.client.HttpClientBuilder httpClient = LangChainChatThinkingProtocol.supports(config)
                    ? LangChainChatThinkingProtocol.httpClientBuilder(baseHttpClient, objectMapper, config)
                    : baseHttpClient;
            OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                    .httpClientBuilder(httpClient)
                    .apiKey(config.apiKey().trim())
                    .baseUrl(config.baseUrl().trim())
                    .modelName(config.model().trim())
                    .parallelToolCalls(true)
                    .listeners(activityCoordinator);
            if (LangChainChatThinkingProtocol.supports(config)) {
                LangChainChatThinkingProtocol.configure(builder, config);
            }
            if (config.reasoningEffort() != null && !config.reasoningEffort().isBlank()) {
                builder.reasoningEffort(config.reasoningEffort().trim());
            }
            return builder.build();
        }
        OpenAiResponsesStreamingChatModel.Builder builder = OpenAiResponsesStreamingChatModel.builder()
                .httpClientBuilder(baseHttpClient)
                .apiKey(config.apiKey().trim())
                .baseUrl(config.baseUrl().trim())
                .modelName(config.model().trim())
                .parallelToolCalls(true)
                .store(false)
                .listeners(activityCoordinator);
        if (config.reasoningEffort() != null && !config.reasoningEffort().isBlank()) {
            String reasoningEffort = config.reasoningEffort().trim();
            builder.reasoningEffort(reasoningEffort);
            if (!"none".equalsIgnoreCase(reasoningEffort)) {
                builder.reasoningSummary("auto");
            }
        }
        return builder.build();
    }

    ChatModel compactionModel(RuntimeModelConfig config, long timeoutSeconds,
                              LangChainActivityEventCoordinator activityCoordinator) {
        Duration timeout = Duration.ofSeconds(timeoutSeconds > 0
                ? timeoutSeconds : properties.getDefaultTimeoutSeconds());
        JdkHttpClientBuilder httpClient = new JdkHttpClientBuilder()
                .connectTimeout(timeout)
                .readTimeout(timeout);
        if (protocol(config) == RuntimeModelProtocol.CHAT_COMPLETIONS) {
            OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                    .httpClientBuilder(httpClient)
                    .apiKey(config.apiKey().trim())
                    .baseUrl(config.baseUrl().trim())
                    .modelName(config.model().trim())
                    .listeners(activityCoordinator)
                    .maxRetries(0)
                    .maxTokens(properties.getCompactionMaxOutputTokens());
            if (config.reasoningEffort() != null && !config.reasoningEffort().isBlank()) {
                builder.reasoningEffort(config.reasoningEffort().trim());
            }
            return builder.build();
        }
        return OpenAiResponsesChatModel.builder()
                .httpClientBuilder(httpClient)
                .apiKey(config.apiKey().trim())
                .baseUrl(config.baseUrl().trim())
                .modelName(config.model().trim())
                .listeners(activityCoordinator)
                .maxOutputTokens(properties.getCompactionMaxOutputTokens())
                .store(false)
                .build();
    }

    ChatModel compactionModel(RuntimeModelConfig config, long timeoutSeconds) {
        return compactionModel(config, timeoutSeconds,
                new LangChainActivityEventCoordinator(event -> { }));
    }

    private RuntimeModelProtocol protocol(RuntimeModelConfig config) {
        return config.protocol() == null ? RuntimeModelProtocol.RESPONSES : config.protocol();
    }

    /**
     * 将任务稳定指令与当前模型补充约束放入 Agent 系统消息，使其在多次模型调用和工具循环中持续生效。
     *
     * @param taskInstructions 当前任务创建时保存的平台与场景稳定指令
     * @param config 当前执行使用的模型配置
     * @return 平台系统提示词、任务稳定指令及当前模型补充约束
     */
    String systemPrompt(String taskInstructions, RuntimeModelConfig config) {
        StringBuilder prompt = new StringBuilder(systemPrompt);
        if (taskInstructions != null && !taskInstructions.isBlank()) {
            prompt.append("\n\n").append(taskInstructions.strip());
        }
        String instruction = config == null ? null : config.instructionPrompt();
        if (instruction == null || instruction.isBlank()) {
            return prompt.toString();
        }
        return prompt.append("\n\n")
                .append(LangChainPromptKit.format("langchain-model-instruction.md", instruction.strip()))
                .toString();
    }

    /**
     * 使用无工具模型请求把调查草稿转换为符合原始场景契约的最终交付。
     *
     * @param request 当前统一 Runtime 请求，提供稳定场景指令和权威原始任务
     * @param draft 工具调查阶段形成的候选答案
     * @param context 当前 LangChain 执行上下文，用于取消控制和用量累计
     * @param activityCoordinator 模型请求与响应状态协调器
     * @param modelCallPolicy 当前执行统一的模型调用重试策略
     * @param round 最终交付消息使用的轮次编号
     * @param executionDeadlineNanos 当前完整执行共享的单调时钟截止点
     * @return 经过最终交付阶段生成并校验的回答文本
     * @throws InterruptedException 等待流式模型响应时线程被中断
     * @throws IllegalStateException 最终交付超时、模型调用失败或返回无效结果
     */
    private String finalizeResponse(RuntimeExecutionRequest request,
                                    String draft,
                                    LangChainExecutionContext context,
                                    LangChainActivityEventCoordinator activityCoordinator,
                                    LangChainModelCallPolicy modelCallPolicy,
                                    int round,
                                    long executionDeadlineNanos) throws InterruptedException {
        if (context.isCancelled()) {
            return null;
        }
        if (executionDeadlineNanos - System.nanoTime() <= 0L) {
            activityCoordinator.executionFailed();
            context.cancel();
            throw new IllegalStateException("最终交付阶段执行超时");
        }
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<ChatResponse> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        StringBuffer partialText = new StringBuffer();
        StringBuffer partialThinking = new StringBuffer();
        StreamingChatModel model = new LangChainRetryingStreamingChatModel(
                new LangChainGuardedStreamingChatModel(
                        streamingModel(request.modelConfig(), request.timeoutSeconds(), activityCoordinator), context),
                modelCallPolicy, context);
        ChatRequest finalRequest = ChatRequest.builder()
                .messages(
                        SystemMessage.from(finalizationSystemPrompt(request.instructions(), request.modelConfig(),
                                request.finalResponseInstructions())),
                        UserMessage.from(finalizationPrompt(request.finalResponsePrompt(), draft)))
                .build();
        model.chat(finalRequest, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                activityCoordinator.modelTextReceived();
                partialText.append(partialResponse);
                context.emitMessageDelta(new RuntimeMessageDelta("round-" + round, partialResponse));
            }

            @Override
            public void onPartialThinking(PartialThinking thinking) {
                appendThinking(context, partialThinking, thinking, round);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                emitModelReasoning(context, response, partialThinking, round);
                responseRef.set(response);
                completed.countDown();
            }

            @Override
            public void onError(Throwable error) {
                errorRef.set(error);
                completed.countDown();
            }
        });
        while (completed.getCount() > 0L) {
            if (context.isCancelled()) {
                return null;
            }
            long remainingNanos = executionDeadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                activityCoordinator.executionFailed();
                context.cancel();
                throw new IllegalStateException("最终交付阶段执行超时");
            }
            completed.await(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(200L)),
                    TimeUnit.NANOSECONDS);
        }
        if (context.isCancelled()) {
            return null;
        }
        if (errorRef.get() != null) {
            activityCoordinator.executionFailed();
            throw new IllegalStateException("最终交付阶段失败：" + errorMessage(errorRef.get()), errorRef.get());
        }
        return requireFinalDelivery(request.executionId(), responseRef.get(), partialText.toString());
    }

    /**
     * 组装最终交付阶段系统消息，确保模型附加规则之后仍以平台场景契约为准。
     *
     * @param taskInstructions 当前任务创建时保存的稳定场景指令
     * @param config 当前执行使用的模型配置
     * @param finalResponseInstructions 平台按场景声明装配的最终答案展示约束
     * @return 包含平台、场景、模型约束和最终交付职责的系统消息
     */
    String finalizationSystemPrompt(String taskInstructions, RuntimeModelConfig config,
                                    String finalResponseInstructions) {
        StringBuilder prompt = new StringBuilder(systemPrompt(taskInstructions, config))
                .append("\n\n")
                .append(LangChainPromptKit.load("langchain-final-delivery-system.md"));
        if (finalResponseInstructions != null && !finalResponseInstructions.isBlank()) {
            prompt.append("\n\n").append(finalResponseInstructions.strip());
        }
        return prompt.toString();
    }

    /**
     * 组装最终交付用户消息，明确区分权威原始任务与不可信调查草稿。
     *
     * @param originalTask 后端保存的原始任务输入
     * @param draft 调查阶段形成的候选答案
     * @return 仅供无工具最终交付请求使用的用户消息
     */
    String finalizationPrompt(String originalTask, String draft) {
        return LangChainPromptKit.format("langchain-final-delivery-user.md",
                originalTask == null ? "" : originalTask.strip(),
                draft == null ? "" : draft.strip());
    }

    /**
     * 校验最终交付模型必须返回纯文本答案，不能重新进入工具调用流程。
     *
     * @param executionId 当前执行 ID，仅用于安全诊断
     * @param response 最终交付模型的完整响应
     * @param partialText 流式过程中累计的文本，供兼容缺少完整文本的响应
     * @return 去除首尾空白后的最终交付文本
     * @throws IllegalStateException 响应为空或仍包含工具调用时抛出
     */
    String requireFinalDelivery(String executionId, ChatResponse response, String partialText) {
        if (response == null || response.aiMessage() == null) {
            throw new IllegalStateException("最终交付阶段未返回响应");
        }
        if (response.aiMessage().hasToolExecutionRequests()) {
            throw new IllegalStateException("最终交付阶段返回了不允许的工具调用");
        }
        String answer = response.aiMessage().text();
        if (answer == null || answer.isBlank()) {
            answer = partialText;
        }
        if (answer == null || answer.isBlank()) {
            log.warn("LangChain final delivery returned empty response executionId={}", executionId);
            throw new IllegalStateException("最终交付阶段返回空结果");
        }
        return answer.strip();
    }

    private void validateRequest(RuntimeExecutionRequest request) {
        if (request == null || request.executionId() == null || request.executionId().isBlank()) {
            throw new IllegalArgumentException("executionId 不能为空");
        }
        if (request.workspace() == null) {
            throw new IllegalArgumentException("workspace 不能为空");
        }
        Path workspace = Path.of(request.workspace().executionRoot()).toAbsolutePath().normalize();
        if (!Files.isDirectory(workspace)) {
            throw new IllegalArgumentException("处理目录不存在：" + workspace);
        }
        if (request.prompt() == null || request.prompt().isBlank()) {
            throw new IllegalArgumentException("prompt 不能为空");
        }
        validateModelConfig(request.modelConfig());
    }

    private void validateModelConfig(RuntimeModelConfig config) {
        if (config == null || config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }
        if (config.baseUrl() == null || config.baseUrl().isBlank()) {
            throw new IllegalArgumentException("API Base URL 不能为空");
        }
        if (config.model() == null || config.model().isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        if (config.contextWindowTokens() != null && config.contextWindowTokens() < 16_000) {
            throw new IllegalArgumentException("模型上下文窗口不能小于 16000 Token");
        }
    }

    private int modelContextWindow(RuntimeModelConfig config) {
        Integer configured = config.contextWindowTokens();
        return configured == null ? properties.getMaxMemoryTokens() : configured;
    }

    private void restoreSessionIfCurrentMemoryMissing(RuntimeSessionRef resumeSession,
                                                      LangChainFileChatMemoryStore chatMemoryStore) {
        if (Files.isRegularFile(chatMemoryStore.memoryFile()) || resumeSession == null) {
            return;
        }
        chatMemoryStore.restoreFrom(sessionPath(resumeSession));
    }

    private void restoreEvidenceIfCurrentIndexMissing(RuntimeSessionRef resumeSession,
                                                      LangChainEvidenceStore evidenceStore) {
        if (Files.isRegularFile(evidenceStore.indexFile()) || resumeSession == null) {
            return;
        }
        evidenceStore.restoreFrom(LangChainEvidenceStore.indexFileForMemory(sessionPath(resumeSession)));
    }

    private Path sessionPath(RuntimeSessionRef session) {
        String sessionPath = session == null ? null : session.sessionPath();
        if (sessionPath == null || sessionPath.isBlank()) {
            throw new IllegalStateException("续聊会话缺少持久化路径："
                    + (session == null ? "unknown" : session.sessionId()));
        }
        return Path.of(sessionPath).toAbsolutePath().normalize();
    }

    private RuntimeSessionRef session(RuntimeExecutionRequest request, Path memoryFile) {
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? request.executionId() : request.conversationId();
        RuntimeSessionRef session = new RuntimeSessionRef(
                "langchain-" + conversationId, memoryFile.toAbsolutePath().normalize().toString());
        sessions.put(conversationId, session);
        return session;
    }

    private RuntimeExecutionResult cancelledResult(String stdout, LangChainExecutionContext context) {
        return new RuntimeExecutionResult(
                130, stdout, "处理已取消", "处理已取消", null, context.usage(), LocalDateTime.now());
    }

    private RuntimeEvent agentMessage(String text, int round, boolean terminal) {
        return new RuntimeEvent(
                RuntimeEventType.AGENT_MESSAGE,
                RuntimeEventStatus.SUCCESS,
                terminal ? "langchain.answer" : "langchain.progress",
                text,
                new RuntimeEventPayload(
                        terminal ? "langchain.answer" : "langchain.progress", "agent_message",
                        "round-" + round, "completed", null, null, null, null, null, null,
                        null, null, null, null, null, false)
                        .withSemantic(terminal ? RuntimeEventSemantic.FINAL_ANSWER : null));
    }

    private RuntimeEvent modelReasoning(String text, int round) {
        return new RuntimeEvent(
                RuntimeEventType.THINKING,
                RuntimeEventStatus.SUCCESS,
                "langchain.model.reasoning",
                text,
                new RuntimeEventPayload(
                        "langchain.model.reasoning", "model_reasoning", "reasoning-round-" + round,
                        "completed", null, null, null, null, null, null,
                        null, null, null, "深度思考", null, false));
    }

    private RuntimeEvent toolExecutedEvent(ToolExecution event,
                                           LangChainCapabilityRegistry capabilityRegistry,
                                           LangChainExecutionContext context) {
        long imageCount = event.resultContents().stream().filter(ImageContent.class::isInstance).count();
        String output = event.hasFailed() || imageCount == 0
                ? event.result()
                : "已载入 " + imageCount + " 张图片";
        return toolEvent(
                event.request().name(), event.request().id(), event.request().arguments(), output,
                event.hasFailed() ? RuntimeEventStatus.FAILED : RuntimeEventStatus.SUCCESS,
                capabilityRegistry, context);
    }

    RuntimeEvent toolEvent(String toolName,
                           String callId,
                           String arguments,
                           String output,
                           RuntimeEventStatus status,
                           LangChainCapabilityRegistry capabilityRegistry) {
        return toolEvent(toolName, callId, arguments, output, status, capabilityRegistry, null);
    }

    RuntimeEvent toolEvent(String toolName,
                           String callId,
                           String arguments,
                           String output,
                           RuntimeEventStatus status,
                           LangChainCapabilityRegistry capabilityRegistry,
                           LangChainExecutionContext context) {
        BuiltInTool builtInTool = BuiltInTool.fromToolName(toolName);
        String actionKey = "tool:" + toolName;
        String actionLabel = builtInTool == null
                ? toolName == null || toolName.isBlank() ? "调用工具" : toolName
                : builtInTool.getLabel();
        String actionTarget = null;
        String visibleArguments = arguments;
        RuntimeActionIcon actionIcon = builtInTool == null ? RuntimeActionIcon.WRENCH : builtInTool.getIcon();
        LangChainExecutionContext.ToolPresentation presentation = context == null
                ? null : context.toolPresentation(toolName);
        if (presentation != null) {
            actionLabel = presentation.label();
            actionIcon = presentation.icon();
        }
        LangChainCapabilityRegistry.NativeCommand nativeCommand = capabilityRegistry == null
                ? null : capabilityRegistry.commandForNativeTool(toolName);
        if (nativeCommand != null) {
            actionKey = (nativeCommand.capabilityCommand() ? "capability:" : "tool:")
                    + nativeCommand.commandKey();
            actionLabel = nativeCommand.name();
            actionTarget = nativeCommand.commandKey();
            actionIcon = nativeCommand.descriptor().icon();
        } else if (builtInTool != null && arguments != null) {
            switch (builtInTool) {
                case RUN_SKILL_COMMAND -> {
                    try {
                        JsonNode values = objectMapper.readTree(arguments);
                        String command = values.path("command").asText("").trim().replaceAll("\\s+", " ");
                        List<String> commandArguments = new ArrayList<>();
                        values.path("arguments").forEach(value -> commandArguments.add(value.asText()));
                        if (!command.isBlank()) {
                            LangChainCapabilityRegistry.SkillCommand skillCommand =
                                    capabilityRegistry.resolveSkillCommand(command);
                            actionKey = "capability:" + skillCommand.command().replace(' ', ':');
                            actionLabel = skillCommand.actionLabel();
                            actionTarget = skillCommand.command();
                            actionIcon = skillCommand.descriptor().icon();
                            visibleArguments = objectMapper.writeValueAsString(Map.of("arguments", commandArguments));
                        }
                    } catch (Exception exception) {
                        log.debug("Cannot resolve Skill event identity", exception);
                    }
                }
                case READ_SKILL_FILE -> {
                    try {
                        JsonNode values = objectMapper.readTree(arguments);
                        String skillName = values.path("skillName").asText("").trim();
                        String relativePath = values.path("relativePath").asText("").trim();
                        if (!skillName.isBlank() && !relativePath.isBlank()) {
                            actionKey = "skill:" + skillName + "/read";
                            actionLabel = "读取" + capabilityRegistry.skillDisplayName(skillName) + " SKILL";
                            actionTarget = relativePath;
                        }
                    } catch (Exception exception) {
                        log.debug("Cannot resolve Skill read event identity", exception);
                    }
                }
                case READ_MCP_TOOL, INVOKE_MCP_TOOL -> {
                    try {
                        JsonNode values = objectMapper.readTree(arguments);
                        String mcpCode = values.path("mcpCode").asText("").trim();
                        String mcpToolName = values.path("toolName").asText("").trim();
                        if (!mcpCode.isBlank() && !mcpToolName.isBlank()) {
                            actionKey = "mcp:" + mcpCode + ":" + mcpToolName;
                            actionTarget = mcpToolName;
                            if (builtInTool == BuiltInTool.INVOKE_MCP_TOOL) {
                                LangChainExecutionContext.ToolPresentation mcpPresentation = context == null
                                        ? null : context.toolPresentation(mcpToolName);
                                actionLabel = mcpPresentation == null
                                        ? "调用 " + mcpToolName : mcpPresentation.label();
                                actionIcon = mcpPresentation == null
                                        ? RuntimeActionIcon.PLUGS_CONNECTED : mcpPresentation.icon();
                                visibleArguments = objectMapper.writeValueAsString(values.path("arguments"));
                            }
                        }
                    } catch (Exception exception) {
                        log.debug("Cannot resolve MCP gateway event identity", exception);
                    }
                }
                default -> {
                    if (builtInTool.getTargetArgument() != null) {
                        try {
                            String target = objectMapper.readTree(arguments)
                                    .path(builtInTool.getTargetArgument()).asText("").trim();
                            if (!target.isBlank()) {
                                actionTarget = target;
                            }
                        } catch (Exception exception) {
                            log.debug("Cannot resolve built-in tool event target", exception);
                        }
                    }
                }
            }
        }
        RuntimeEventPayload payload = new RuntimeEventPayload(
                "langchain.tool", "tool_call", callId, status.name().toLowerCase(), null,
                toolName, callId, visibleArguments, output, null, null,
                actionKey, callId, actionLabel, actionTarget, false)
                .withActionIcon(actionIcon);
        return new RuntimeEvent(RuntimeEventType.COMMAND, status, actionKey, output, payload);
    }

    @Getter
    private enum BuiltInTool {
        WRITE_WORKSPACE_FILE("write_workspace_file", "写入文件", RuntimeActionIcon.FILE_PLUS, null),
        RUN_SKILL_COMMAND("run_skill_command", "执行能力命令", RuntimeActionIcon.TERMINAL, null),
        READ_SKILL_FILE("read_skill_file", "读取 Skill 说明", RuntimeActionIcon.BOOK_OPEN, null),
        LIST_MCP_TOOLS("list_mcp_tools", "查看 MCP 工具", RuntimeActionIcon.PLUGS_CONNECTED, null),
        READ_MCP_TOOL("read_mcp_tool", "读取 MCP 工具说明", RuntimeActionIcon.BOOK_OPEN, null),
        INVOKE_MCP_TOOL("invoke_mcp_tool", "调用 MCP 工具", RuntimeActionIcon.PLUGS_CONNECTED, null),
        GLOB("glob", "查找文件", RuntimeActionIcon.FILE_MAGNIFYING_GLASS, null),
        GREP("grep", "搜索文件内容", RuntimeActionIcon.MAGNIFYING_GLASS, null),
        READ_FILE("read_file", "读取文件", RuntimeActionIcon.FILE_TEXT, "relativePath"),
        READ_EVIDENCE("read_evidence", "读取上下文证据", RuntimeActionIcon.FILE_TEXT, "evidenceId"),
        QUERY_EVIDENCE("query_evidence", "查询上下文证据", RuntimeActionIcon.BRACKETS_CURLY, "evidenceId"),
        QUERY_JSON("query_json", "查询 JSON", RuntimeActionIcon.BRACKETS_CURLY, "relativePath");

        private final String toolName;
        private final String label;
        private final RuntimeActionIcon icon;
        private final String targetArgument;

        BuiltInTool(String toolName, String label, RuntimeActionIcon icon, String targetArgument) {
            this.toolName = toolName;
            this.label = label;
            this.icon = icon;
            this.targetArgument = targetArgument;
        }

        /**
         * 根据模型工具名称解析内置展示定义。
         *
         * @param toolName 模型 Tool Schema 中的真实名称
         * @return 匹配的内置工具定义，未知工具返回 null
         */
        private static BuiltInTool fromToolName(String toolName) {
            for (BuiltInTool tool : values()) {
                if (tool.toolName.equals(toolName)) {
                    return tool;
                }
            }
            return null;
        }

    }

    private String responseText(ChatResponse response, StringBuffer partialText) {
        String text = response.aiMessage().text();
        return text == null || text.isBlank() ? partialText.toString().strip() : text.strip();
    }

    private void appendThinking(LangChainExecutionContext context, StringBuffer buffer,
                                PartialThinking partialThinking, int round) {
        if (partialThinking != null && partialThinking.text() != null) {
            String delta = partialThinking.text();
            buffer.append(delta);
            context.emitMessageDelta(new RuntimeMessageDelta(
                    "reasoning-round-" + round, delta, RuntimeMessageDeltaType.REASONING));
        }
    }

    private void emitModelReasoning(LangChainExecutionContext context, ChatResponse response,
                                    StringBuffer partialThinking, int round) {
        String completed = response == null || response.aiMessage() == null
                ? null : response.aiMessage().thinking();
        String thinking = completed == null || completed.isBlank()
                ? partialThinking.toString().strip() : completed.strip();
        if (!thinking.isBlank()) {
            context.emit(modelReasoning(thinking, round));
        }
    }

    /**
     * 校验模型最终响应是否包含回答文本或待执行工具，避免把上游空响应记录为成功。
     *
     * @param executionId 当前执行 ID，仅用于安全诊断日志
     * @param response LangChain4j 返回的最终响应
     * @return 去除首尾空白后的回答文本；仅有工具调用时返回空字符串
     * @throws IllegalStateException 最终响应既没有回答文本也没有工具调用时抛出
     */
    String requireValidFinalResponse(String executionId, ChatResponse response) {
        String answer = response.aiMessage() == null || response.aiMessage().text() == null
                ? "" : response.aiMessage().text().strip();
        boolean hasToolRequests = response.aiMessage() != null
                && response.aiMessage().hasToolExecutionRequests();
        if (!answer.isBlank() || hasToolRequests) {
            return answer;
        }

        var tokenUsage = response.tokenUsage();
        log.warn("LangChain model returned empty response executionId={} responseId={} model={} "
                        + "finishReason={} inputTokens={} outputTokens={} totalTokens={}",
                executionId,
                response.id(),
                response.modelName(),
                response.finishReason(),
                tokenUsage == null ? null : tokenUsage.inputTokenCount(),
                tokenUsage == null ? null : tokenUsage.outputTokenCount(),
                tokenUsage == null ? null : tokenUsage.totalTokenCount());
        throw new IllegalStateException("模型返回空响应，请重试；若持续发生请检查模型服务兼容性");
    }

    private void appendLine(StringBuffer target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append(System.lineSeparator());
        }
        target.append(value.strip());
    }

    private String errorMessage(Throwable error) {
        return LangChainErrorMessageKit.userMessage(error);
    }

    private record ExecutionHandle(LangChainExecutionContext context, CountDownLatch completed,
                                   LangChainActivityEventCoordinator activityCoordinator) {
    }
}
