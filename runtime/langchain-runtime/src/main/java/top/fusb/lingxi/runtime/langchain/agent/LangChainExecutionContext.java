package top.fusb.lingxi.runtime.langchain.agent;

import top.fusb.lingxi.runtime.api.event.RuntimeActionIcon;
import top.fusb.lingxi.runtime.api.event.RuntimeEvent;
import top.fusb.lingxi.runtime.api.event.RuntimeEventListener;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionEnvironment;
import top.fusb.lingxi.runtime.api.execution.RuntimeToolExecutionMode;
import top.fusb.lingxi.runtime.api.execution.RuntimeWorkspaceLayout;
import top.fusb.lingxi.runtime.api.model.RuntimeMessageDelta;
import top.fusb.lingxi.runtime.api.model.RuntimeUsage;
import top.fusb.lingxi.runtime.langchain.process.LangChainProcessRunner;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
public final class LangChainExecutionContext {

    private final String executionId;
    private final Path workspace;
    private final RuntimeWorkspaceLayout workspaceLayout;
    private final RuntimeEventListener listener;
    private final RuntimeExecutionEnvironment environment;
    private final Long modelContextWindow;
    private final int maxToolCalls;
    private final int maxEvidenceReads;
    private final int maxRepeatedCapabilityCalls;
    private final long maxModelTokens;
    private final long modelTokenFinalizationThreshold;
    private final Object listenerLock = new Object();
    private final ReentrantReadWriteLock toolExecutionLock = new ReentrantReadWriteLock(true);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean finalizationMode = new AtomicBoolean();
    private final Set<Process> processes = ConcurrentHashMap.newKeySet();
    private final Map<Long, ToolInvocation> toolInvocations = new ConcurrentHashMap<>();
    private final AtomicReference<StreamingHandle> streamingHandle = new AtomicReference<>();
    private final Map<CapabilityCall, AtomicLong> capabilityCalls = new ConcurrentHashMap<>();
    private final Map<String, ToolPresentation> toolPresentations = new ConcurrentHashMap<>();
    private final AtomicLong requestCount = new AtomicLong();
    private final AtomicLong toolCallCount = new AtomicLong();
    private final AtomicLong toolInvocationSequence = new AtomicLong();
    private final AtomicLong evidenceReadCount = new AtomicLong();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong cachedInputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong reasoningOutputTokens = new AtomicLong();
    private final AtomicLong totalTokens = new AtomicLong();
    private final AtomicLong lastInputTokens = new AtomicLong();
    private final AtomicLong lastCachedInputTokens = new AtomicLong();
    private final AtomicLong lastOutputTokens = new AtomicLong();
    private final AtomicLong lastReasoningOutputTokens = new AtomicLong();
    private final AtomicLong lastTotalTokens = new AtomicLong();

    public LangChainExecutionContext(String executionId, Path workspace, RuntimeEventListener listener) {
        this(executionId, workspaceOnly(workspace), listener, RuntimeExecutionEnvironment.empty(), null,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE, 0L);
    }

    public LangChainExecutionContext(String executionId, Path workspace, RuntimeEventListener listener,
                                     Long modelContextWindow) {
        this(executionId, workspaceOnly(workspace), listener, RuntimeExecutionEnvironment.empty(), modelContextWindow,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE, 0L);
    }

    public LangChainExecutionContext(String executionId, Path workspace, RuntimeEventListener listener,
                                     RuntimeExecutionEnvironment environment, Long modelContextWindow,
                                     int maxToolCalls, int maxRepeatedCapabilityCalls, long maxModelTokens) {
        this(executionId, workspaceOnly(workspace), listener, environment, modelContextWindow,
                maxToolCalls, maxRepeatedCapabilityCalls, maxModelTokens, 0L);
    }

    public LangChainExecutionContext(String executionId, Path workspace, RuntimeEventListener listener,
                                     RuntimeExecutionEnvironment environment, Long modelContextWindow,
                                     int maxToolCalls, int maxRepeatedCapabilityCalls, long maxModelTokens,
                                     long modelTokenFinalizationReserve) {
        this(executionId, workspaceOnly(workspace), listener, environment, modelContextWindow,
                maxToolCalls, maxRepeatedCapabilityCalls, maxModelTokens, modelTokenFinalizationReserve);
    }

    public LangChainExecutionContext(String executionId, RuntimeWorkspaceLayout workspaceLayout,
                                     RuntimeEventListener listener,
                                     RuntimeExecutionEnvironment environment, Long modelContextWindow,
                                     int maxToolCalls, int maxRepeatedCapabilityCalls, long maxModelTokens,
                                     long modelTokenFinalizationReserve) {
        this(executionId, workspaceLayout, listener, environment, modelContextWindow,
                maxToolCalls, maxToolCalls, maxRepeatedCapabilityCalls, maxModelTokens,
                modelTokenFinalizationReserve);
    }

    public LangChainExecutionContext(String executionId, RuntimeWorkspaceLayout workspaceLayout,
                                     RuntimeEventListener listener,
                                     RuntimeExecutionEnvironment environment, Long modelContextWindow,
                                     int maxToolCalls, int maxEvidenceReads, int maxRepeatedCapabilityCalls,
                                     long maxModelTokens, long modelTokenFinalizationReserve) {
        this.executionId = executionId;
        this.workspaceLayout = workspaceLayout;
        this.workspace = Path.of(workspaceLayout.executionRoot()).toAbsolutePath().normalize();
        this.listener = listener;
        this.environment = environment == null ? RuntimeExecutionEnvironment.empty() : environment;
        this.modelContextWindow = modelContextWindow;
        this.maxToolCalls = maxToolCalls;
        this.maxEvidenceReads = maxEvidenceReads;
        this.maxRepeatedCapabilityCalls = maxRepeatedCapabilityCalls;
        this.maxModelTokens = maxModelTokens <= 0L ? Long.MAX_VALUE : maxModelTokens;
        this.modelTokenFinalizationThreshold = this.maxModelTokens == Long.MAX_VALUE
                || modelTokenFinalizationReserve <= 0L
                ? Long.MAX_VALUE : Math.max(0L, this.maxModelTokens - modelTokenFinalizationReserve);
    }

    public LangChainExecutionContext(String executionId, Path workspace, RuntimeEventListener listener,
                                     Long modelContextWindow, int maxToolCalls, int maxRepeatedCapabilityCalls,
                                     long maxModelTokens) {
        this(executionId, workspaceOnly(workspace), listener, RuntimeExecutionEnvironment.empty(), modelContextWindow,
                maxToolCalls, maxRepeatedCapabilityCalls, maxModelTokens, 0L);
    }

    public String executionId() {
        return executionId;
    }

    public Path workspace() {
        return workspace;
    }

    public RuntimeWorkspaceLayout workspaceLayout() {
        return workspaceLayout;
    }

    private static RuntimeWorkspaceLayout workspaceOnly(Path workspace) {
        String root = workspace.toAbsolutePath().normalize().toString();
        return new RuntimeWorkspaceLayout(root, root, root, root, root, root, root);
    }

    public RuntimeExecutionEnvironment environment() {
        return environment;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void emit(RuntimeEvent event) {
        if (!cancelled.get() && event != null) {
            synchronized (listenerLock) {
                if (!cancelled.get()) {
                    listener.onEvent(event);
                }
            }
        }
    }

    public void emitMessageDelta(RuntimeMessageDelta delta) {
        if (!cancelled.get() && delta != null) {
            synchronized (listenerLock) {
                if (!cancelled.get()) {
                    listener.onMessageDelta(delta);
                }
            }
        }
    }

    /**
     * 登记动态 Tool 在当前执行中的展示信息，供统一事件转换使用。
     *
     * @param toolName 模型 Tool Schema 中的真实名称
     * @param label 面向用户的动作名称
     * @param icon 与具体前端图标库无关的图标语义
     * @return 无返回值
     */
    public void registerToolPresentation(String toolName, String label, RuntimeActionIcon icon) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        toolPresentations.put(toolName, new ToolPresentation(label, icon));
    }

    public ToolPresentation toolPresentation(String toolName) {
        return toolName == null ? null : toolPresentations.get(toolName);
    }

    public void beginModelRequest() {
        ensureActive();
        if (totalTokens.get() >= maxModelTokens) {
            throw new LangChainExecutionLimitException(
                    "本次任务的模型 Token 用量已达到执行上限：" + maxModelTokens);
        }
        requestCount.incrementAndGet();
        notifyUsage();
    }

    public void addUsage(TokenUsage usage) {
        if (usage == null || cancelled.get()) {
            return;
        }
        synchronized (listenerLock) {
            if (cancelled.get()) {
                return;
            }
            long input = value(usage.inputTokenCount());
            long output = value(usage.outputTokenCount());
            long total = usage.totalTokenCount() == null ? input + output : usage.totalTokenCount();
            long cached = 0L;
            long reasoning = 0L;
            if (usage instanceof OpenAiTokenUsage openAiUsage) {
                if (openAiUsage.inputTokensDetails() != null) {
                    cached = value(openAiUsage.inputTokensDetails().cachedTokens());
                }
                if (openAiUsage.outputTokensDetails() != null) {
                    reasoning = value(openAiUsage.outputTokensDetails().reasoningTokens());
                }
            }
            inputTokens.addAndGet(input);
            cachedInputTokens.addAndGet(cached);
            outputTokens.addAndGet(output);
            reasoningOutputTokens.addAndGet(reasoning);
            totalTokens.addAndGet(total);
            lastInputTokens.set(input);
            lastCachedInputTokens.set(cached);
            lastOutputTokens.set(output);
            lastReasoningOutputTokens.set(reasoning);
            lastTotalTokens.set(total);
            listener.onUsage(usage());
        }
    }

    public void beginToolCall(String toolName) {
        ensureActive();
        if (finalizationRequired()) {
            throw new LangChainExecutionLimitException("本次任务已进入收尾阶段，不再执行新的工具调用");
        }
        long next = toolCallCount.incrementAndGet();
        if (next > maxToolCalls) {
            toolCallCount.decrementAndGet();
            throw new LangChainExecutionLimitException("工具调用次数已达到本次执行上限：" + maxToolCalls);
        }
    }

    /**
     * 按工具声明获取当前执行的并发许可。只读工具共享读锁，其他工具独占执行。
     *
     * @param executionMode 工具声明的执行语义，null 按串行处理
     * @return 必须在工具调用结束时关闭的执行许可
     * @throws InterruptedException 等待执行许可时线程被中断
     * @throws IllegalStateException 当前任务已经取消
     */
    public ToolExecutionPermit acquireToolExecution(RuntimeToolExecutionMode executionMode)
            throws InterruptedException {
        ensureActive();
        Lock lock = executionMode == RuntimeToolExecutionMode.READ_ONLY
                ? toolExecutionLock.readLock() : toolExecutionLock.writeLock();
        lock.lockInterruptibly();
        try {
            ensureActive();
            return new ToolExecutionPermit(lock);
        } catch (RuntimeException exception) {
            lock.unlock();
            throw exception;
        }
    }

    /**
     * 将当前线程登记为当前执行的一个 Tool 子调用，使取消信号能够中断到具体工具。
     *
     * @param toolCallId 模型生成的 Tool Call ID，可为空
     * @param toolName 实际执行的工具名称，可为空
     * @return 必须在工具执行结束时关闭的调用句柄
     * @throws IllegalStateException 当前任务已经取消
     */
    public ToolInvocation beginToolInvocation(String toolCallId, String toolName) {
        ensureActive();
        long sequence = toolInvocationSequence.incrementAndGet();
        ToolInvocation invocation = new ToolInvocation(sequence, toolCallId, toolName, Thread.currentThread());
        toolInvocations.put(sequence, invocation);
        if (cancelled.get()) {
            invocation.cancelFromExecution();
            invocation.close();
            throw new IllegalStateException("任务执行已取消");
        }
        return invocation;
    }

    /**
     * 登记一次从当前任务 Evidence 恢复已归档上下文的读取，不占用外部调查工具预算。
     *
     * @return 无返回值；超过独立回读预算或进入收尾阶段时抛出执行限制异常
     * @throws LangChainExecutionLimitException 当前执行已取消、进入收尾阶段或回读次数超限时抛出
     */
    public void beginEvidenceRead() {
        ensureActive();
        if (finalizationRequired()) {
            throw new LangChainExecutionLimitException("本次任务已进入收尾阶段，不再读取上下文证据");
        }
        long next = evidenceReadCount.incrementAndGet();
        if (next > maxEvidenceReads) {
            evidenceReadCount.decrementAndGet();
            throw new LangChainExecutionLimitException(
                    "Evidence 读取次数已达到本次执行上限：" + maxEvidenceReads);
        }
    }

    public void beginCapabilityCall(String commandKey, List<String> arguments) {
        beginToolCall(commandKey);
        CapabilityCall signature = new CapabilityCall(commandKey,
                arguments == null ? List.of() : List.copyOf(arguments));
        long repeated = capabilityCalls.computeIfAbsent(signature, ignored -> new AtomicLong()).incrementAndGet();
        if (repeated > maxRepeatedCapabilityCalls) {
            throw new LangChainExecutionLimitException(
                    "相同能力命令和参数已达到重复调用上限：" + maxRepeatedCapabilityCalls);
        }
    }

    /**
     * 判断累计模型用量是否已经进入预留的最终交付区间。
     *
     * @return 需要停止工具探索并立即生成最终回答时返回 true，否则返回 false
     */
    public boolean finalizationRequired() {
        if (totalTokens.get() >= modelTokenFinalizationThreshold && finalizationMode.compareAndSet(false, true)) {
            log.info("Model token budget entered finalization mode executionId={} usedTokens={} "
                            + "finalizationThreshold={} maxTokens={}",
                    executionId, totalTokens.get(), modelTokenFinalizationThreshold, maxModelTokens);
        }
        return finalizationMode.get();
    }

    /**
     * 在探索阶段达到时间上限时进入收尾模式，后续模型请求不再携带工具定义。
     *
     * @return 本次调用是否首次将执行切换到收尾模式
     */
    public boolean enterTimeFinalization() {
        boolean entered = finalizationMode.compareAndSet(false, true);
        if (entered) {
            log.info("Execution deadline entered finalization mode executionId={} usedTokens={} requests={} "
                            + "toolCalls={} evidenceReads={}",
                    executionId, totalTokens.get(), requestCount.get(), toolCallCount.get(), evidenceReadCount.get());
        }
        return entered;
    }

    public void registerStreamingHandle(StreamingHandle handle) {
        if (handle == null) {
            return;
        }
        streamingHandle.set(handle);
        if (cancelled.get() && !handle.isCancelled()) {
            handle.cancel();
        }
    }

    public void clearStreamingHandle(StreamingHandle handle) {
        streamingHandle.compareAndSet(handle, null);
    }

    public RuntimeUsage usage() {
        long requests = requestCount.get();
        if (requests == 0) {
            return null;
        }
        return new RuntimeUsage(
                Instant.now().toString(), requests, inputTokens.get(), cachedInputTokens.get(), null,
                outputTokens.get(), reasoningOutputTokens.get(), totalTokens.get(), lastInputTokens.get(),
                lastCachedInputTokens.get(), null, lastOutputTokens.get(), lastReasoningOutputTokens.get(),
                lastTotalTokens.get(), modelContextWindow
        );
    }

    public void register(Process process) {
        if (process != null) {
            processes.add(process);
            if (cancelled.get()) {
                LangChainProcessRunner.destroy(process);
            }
        }
    }

    public void unregister(Process process) {
        processes.remove(process);
    }

    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return false;
        }
        stopCurrentWork();
        return true;
    }

    /**
     * 停止当前模型流、工具调用和子进程，但不把执行标记为用户取消。
     *
     * @return 无返回值
     */
    public void stopCurrentWork() {
        StreamingHandle handle = streamingHandle.getAndSet(null);
        if (handle != null && !handle.isCancelled()) {
            handle.cancel();
        }
        toolInvocations.values().forEach(ToolInvocation::cancelFromExecution);
        processes.forEach(LangChainProcessRunner::destroy);
    }

    private void notifyUsage() {
        synchronized (listenerLock) {
            if (!cancelled.get()) {
                listener.onUsage(usage());
            }
        }
    }

    private void ensureActive() {
        if (cancelled.get()) {
            throw new IllegalStateException("任务执行已取消");
        }
    }

    private long value(Integer value) {
        return value == null ? 0L : value.longValue();
    }

    private record CapabilityCall(String commandKey, List<String> arguments) {
    }

    public record ToolPresentation(String label, RuntimeActionIcon icon) {
    }

    public static final class ToolExecutionPermit implements AutoCloseable {

        private final Lock lock;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ToolExecutionPermit(Lock lock) {
            this.lock = lock;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                lock.unlock();
            }
        }
    }

    public final class ToolInvocation implements AutoCloseable {

        private final long sequence;
        private final String toolCallId;
        private final String toolName;
        private final Thread thread;
        private final AtomicReference<ToolInvocationStatus> status =
                new AtomicReference<>(ToolInvocationStatus.RUNNING);
        private final AtomicBoolean closed = new AtomicBoolean();

        private ToolInvocation(long sequence, String toolCallId, String toolName, Thread thread) {
            this.sequence = sequence;
            this.toolCallId = toolCallId;
            this.toolName = toolName;
            this.thread = thread;
        }

        public void success() {
            status.compareAndSet(ToolInvocationStatus.RUNNING, ToolInvocationStatus.SUCCESS);
        }

        public void failed() {
            status.compareAndSet(ToolInvocationStatus.RUNNING, ToolInvocationStatus.FAILED);
        }

        public void aborted() {
            status.compareAndSet(ToolInvocationStatus.RUNNING, ToolInvocationStatus.ABORTED);
        }

        public boolean isAborted() {
            return status.get() == ToolInvocationStatus.ABORTED;
        }

        private void cancelFromExecution() {
            if (status.compareAndSet(ToolInvocationStatus.RUNNING, ToolInvocationStatus.ABORTED)) {
                log.info("Tool invocation cancelled executionId={} toolCallId={} toolName={}",
                        executionId, toolCallId, toolName);
                thread.interrupt();
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                if (status.get() == ToolInvocationStatus.RUNNING) {
                    failed();
                }
                toolInvocations.remove(sequence, this);
            }
        }
    }

    private enum ToolInvocationStatus {
        RUNNING,
        SUCCESS,
        FAILED,
        ABORTED
    }
}
