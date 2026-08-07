package top.fusb.lingxi.runtime.langchain.agent.memory;

import top.fusb.lingxi.runtime.langchain.agent.model.LangChainContextBudget;
import top.fusb.lingxi.runtime.langchain.util.LangChainPromptKit;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
public final class LangChainCompactingChatMemory implements ChatMemory {

    private final Object id;
    private final LangChainFileChatMemoryStore transcriptStore;
    private final LangChainCompactionStateStore stateStore;
    private final TokenCountEstimator tokenEstimator;
    private final LangChainContextBudget contextBudget;
    private final LangChainConversationCompactor compactor;
    private final int inputTokenBudget;
    private final int recentMaximumTokens;
    private final int recentMinimumTokens;
    private final int recentTurns;
    private final int minimumCompactionTokens;
    private final int retainedToolOutputTokens;
    private final int toolOutputPruneMinimumTokens;
    private final Runnable compactionListener;
    private final LangChainActiveToolResultProjector activeToolResultProjector;
    private List<ChatMessage> transcript;
    private LangChainCompactionStateStore.State state;
    private int failedCompactionBoundary = -1;

    public LangChainCompactingChatMemory(Object id,
                                  LangChainFileChatMemoryStore transcriptStore,
                                  LangChainCompactionStateStore stateStore,
                                  TokenCountEstimator tokenEstimator,
                                  LangChainConversationCompactor compactor,
                                  int inputTokenBudget,
                                  double compactionTriggerRatio,
                                  int recentMaximumTokens,
                                  int recentMinimumTokens,
                                  int recentTurns,
                                  int minimumCompactionTokens,
                                  int retainedToolOutputTokens,
                                  int toolOutputPruneMinimumTokens) {
        this(id, transcriptStore, stateStore, tokenEstimator, compactor, inputTokenBudget,
                compactionTriggerRatio, recentMaximumTokens, recentMinimumTokens, recentTurns,
                minimumCompactionTokens, retainedToolOutputTokens, toolOutputPruneMinimumTokens, () -> { }, null);
    }

    public LangChainCompactingChatMemory(Object id,
                                  LangChainFileChatMemoryStore transcriptStore,
                                  LangChainCompactionStateStore stateStore,
                                  TokenCountEstimator tokenEstimator,
                                  LangChainConversationCompactor compactor,
                                  int inputTokenBudget,
                                  double compactionTriggerRatio,
                                  int recentMaximumTokens,
                                  int recentMinimumTokens,
                                  int recentTurns,
                                  int minimumCompactionTokens,
                                  int retainedToolOutputTokens,
                                  int toolOutputPruneMinimumTokens,
                                  Runnable compactionListener) {
        this(id, transcriptStore, stateStore, tokenEstimator, compactor, inputTokenBudget,
                compactionTriggerRatio, recentMaximumTokens, recentMinimumTokens, recentTurns,
                minimumCompactionTokens, retainedToolOutputTokens, toolOutputPruneMinimumTokens,
                compactionListener, null);
    }

    public LangChainCompactingChatMemory(Object id,
                                  LangChainFileChatMemoryStore transcriptStore,
                                  LangChainCompactionStateStore stateStore,
                                  TokenCountEstimator tokenEstimator,
                                  LangChainConversationCompactor compactor,
                                  int inputTokenBudget,
                                  double compactionTriggerRatio,
                                  int recentMaximumTokens,
                                  int recentMinimumTokens,
                                  int recentTurns,
                                  int minimumCompactionTokens,
                                  int retainedToolOutputTokens,
                                  int toolOutputPruneMinimumTokens,
                                  Runnable compactionListener,
                                  LangChainActiveToolResultProjector activeToolResultProjector) {
        this(id, transcriptStore, stateStore,
                new LangChainContextBudget(tokenEstimator, inputTokenBudget, compactionTriggerRatio, null, null),
                compactor, recentMaximumTokens, recentMinimumTokens, recentTurns, minimumCompactionTokens,
                retainedToolOutputTokens, toolOutputPruneMinimumTokens, compactionListener,
                activeToolResultProjector);
    }

    public LangChainCompactingChatMemory(Object id,
                                  LangChainFileChatMemoryStore transcriptStore,
                                  LangChainCompactionStateStore stateStore,
                                  LangChainContextBudget contextBudget,
                                  LangChainConversationCompactor compactor,
                                  int recentMaximumTokens,
                                  int recentMinimumTokens,
                                  int recentTurns,
                                  int minimumCompactionTokens,
                                  int retainedToolOutputTokens,
                                  int toolOutputPruneMinimumTokens,
                                  Runnable compactionListener,
                                  LangChainActiveToolResultProjector activeToolResultProjector) {
        if (contextBudget == null || contextBudget.inputTokenBudget() < 1_000
                || recentMaximumTokens < 1_000 || recentMinimumTokens < 1_000
                || recentMinimumTokens > recentMaximumTokens || recentTurns < 1
                || minimumCompactionTokens < 1_000 || retainedToolOutputTokens < 1_000
                || toolOutputPruneMinimumTokens < 1_000) {
            throw new IllegalArgumentException("LangChain 上下文压缩参数无效");
        }
        this.id = id;
        this.transcriptStore = transcriptStore;
        this.stateStore = stateStore;
        this.contextBudget = contextBudget;
        this.tokenEstimator = contextBudget.tokenEstimator();
        this.compactor = compactor;
        this.inputTokenBudget = contextBudget.inputTokenBudget();
        this.recentMaximumTokens = Math.min(recentMaximumTokens, Math.max(1_000, this.inputTokenBudget / 2));
        this.recentMinimumTokens = Math.min(recentMinimumTokens, this.recentMaximumTokens);
        this.recentTurns = recentTurns;
        this.minimumCompactionTokens = minimumCompactionTokens;
        this.retainedToolOutputTokens = retainedToolOutputTokens;
        this.toolOutputPruneMinimumTokens = toolOutputPruneMinimumTokens;
        this.compactionListener = compactionListener == null ? () -> { } : compactionListener;
        this.activeToolResultProjector = activeToolResultProjector;
        this.transcript = new ArrayList<>(transcriptStore.getMessages(id));
        this.state = normalizeState(stateStore.read());
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public synchronized void add(ChatMessage message) {
        if (message instanceof SystemMessage systemMessage) {
            int existingIndex = firstIndex(SystemMessage.class);
            if (existingIndex >= 0 && transcript.get(existingIndex).equals(systemMessage)) {
                return;
            }
            if (existingIndex >= 0) {
                transcript.set(existingIndex, systemMessage);
            } else {
                transcript.add(0, systemMessage);
                state = new LangChainCompactionStateStore.State(
                        state.compactedMessageCount() == 0 ? 0 : state.compactedMessageCount() + 1,
                        state.summary());
            }
        } else {
            transcript.add(message);
        }
        transcriptStore.updateMessages(id, transcript);
        compactIfNeeded();
    }

    @Override
    public synchronized void set(Iterable<ChatMessage> messages) {
        List<ChatMessage> replacement = new ArrayList<>();
        messages.forEach(replacement::add);
        transcript = replacement;
        state = LangChainCompactionStateStore.State.empty();
        transcriptStore.updateMessages(id, transcript);
        stateStore.clear();
        compactIfNeeded();
    }

    @Override
    public synchronized List<ChatMessage> messages() {
        compactIfNeeded();
        List<ChatMessage> projected = activeMessages();
        if (activeToolResultProjector != null) {
            projected = activeToolResultProjector.projectCurrentTurn(projected, false);
            if (contextBudget.estimateMessages(projected).exceedsInputBudget()) {
                projected = activeToolResultProjector.projectCurrentTurn(projected, true);
            }
        }
        LangChainContextBudget.Snapshot projectedBudget = contextBudget.estimateMessages(projected);
        ensureWithinHardLimit(projected, projectedBudget);
        return List.copyOf(projected);
    }

    @Override
    public synchronized void clear() {
        transcript = new ArrayList<>();
        state = LangChainCompactionStateStore.State.empty();
        transcriptStore.deleteMessages(id);
        stateStore.clear();
    }

    /**
     * 将 Agent 调查阶段写入的最后一条草稿替换为实际交付给用户的最终回答。
     *
     * @param finalResponse 已通过最终交付校验的回答文本
     * @return 无返回值
     * @throws IllegalArgumentException 最终回答为空时抛出
     * @throws IllegalStateException 当前会话末尾不是可替换的普通助手消息时抛出
     */
    public synchronized void commitFinalResponse(String finalResponse) {
        if (finalResponse == null || finalResponse.isBlank()) {
            throw new IllegalArgumentException("LangChain 最终回答不能为空");
        }
        int lastIndex = transcript.size() - 1;
        if (lastIndex < 0 || !(transcript.get(lastIndex) instanceof AiMessage draft)
                || draft.hasToolExecutionRequests()) {
            throw new IllegalStateException("LangChain 会话缺少可替换的调查草稿");
        }
        transcript.set(lastIndex, AiMessage.from(finalResponse.strip()));
        transcriptStore.updateMessages(id, transcript);
        compactIfNeeded();
    }

    private void compactIfNeeded() {
        if (hasIncompleteTrailingToolStep()) {
            return;
        }
        List<ChatMessage> active = activeMessages();
        LangChainContextBudget.Snapshot activeBudget = contextBudget.estimateMessages(active);
        if (!activeBudget.shouldCompact()) {
            return;
        }
        int originalTaskIndex = firstIndex(UserMessage.class);
        if (originalTaskIndex < 0) {
            return;
        }
        int immutableEnd = originalTaskIndex + 1;
        int candidateStart = Math.max(immutableEnd, state.compactedMessageCount());
        int tailStart = recentTailStart(immutableEnd);
        if (tailStart <= candidateStart) {
            ensureWithinHardLimit(active, activeBudget);
            return;
        }
        List<ChatMessage> candidate = List.copyOf(pruneOldToolOutputs(
                new ArrayList<>(transcript.subList(candidateStart, tailStart))));
        int candidateTokens = tokenEstimator.estimateTokenCountInMessages(candidate);
        if (candidateTokens < minimumCompactionTokens) {
            ensureWithinHardLimit(active, activeBudget);
            return;
        }
        if (tailStart <= failedCompactionBoundary) {
            ensureWithinHardLimit(active, activeBudget);
            return;
        }
        try {
            String summary = compactor.compact(transcript.get(originalTaskIndex), state.summary(), candidate);
            state = new LangChainCompactionStateStore.State(tailStart, summary);
            failedCompactionBoundary = -1;
            stateStore.write(state);
            List<ChatMessage> compactedMessages = activeMessages();
            LangChainContextBudget.Snapshot compactedBudget = contextBudget.estimateMessages(compactedMessages);
            log.info("LangChain context compacted memoryId={} transcriptMessages={} compactedMessages={} "
                            + "beforeTokens={} afterTokens={}",
                    id, transcript.size(), tailStart, activeBudget.estimatedInputTokens(),
                    compactedBudget.estimatedInputTokens());
            compactionListener.run();
            ensureWithinHardLimit(compactedMessages, compactedBudget);
        } catch (RuntimeException exception) {
            failedCompactionBoundary = tailStart;
            log.warn("LangChain context compaction failed memoryId={} activeTokens={}",
                    id, activeBudget.estimatedInputTokens(), exception);
            ensureWithinHardLimit(active, activeBudget);
        }
    }

    private List<ChatMessage> activeMessages() {
        List<ChatMessage> active;
        if (state.compactedMessageCount() <= 0 || state.summary() == null || state.summary().isBlank()) {
            active = new ArrayList<>(transcript);
        } else {
            int originalTaskIndex = firstIndex(UserMessage.class);
            int immutableEnd = originalTaskIndex < 0 ? 0 : originalTaskIndex + 1;
            int boundary = Math.max(immutableEnd, Math.min(state.compactedMessageCount(), transcript.size()));
            active = new ArrayList<>(transcript.subList(0, immutableEnd));
            active.add(AiMessage.from(LangChainPromptKit.format(
                    "langchain-compaction-summary.md", state.summary())));
            active.addAll(transcript.subList(boundary, transcript.size()));
        }
        if (!contextBudget.estimateMessages(active).shouldCompact()) {
            return active;
        }
        return pruneOldToolOutputs(active);
    }

    private int recentTailStart(int immutableEnd) {
        List<MessageBlock> blocks = messageBlocks(immutableEnd);
        int tokens = 0;
        int includedBlocks = 0;
        int tailStart = transcript.size();
        for (int index = blocks.size() - 1; index >= 0; index--) {
            MessageBlock block = blocks.get(index);
            int blockTokens = tokenEstimator.estimateTokenCountInMessages(
                    transcript.subList(block.startInclusive(), block.endExclusive()));
            boolean required = includedBlocks < recentTurns || tokens < recentMinimumTokens;
            if (!required && tokens + blockTokens > recentMaximumTokens) {
                break;
            }
            tokens += blockTokens;
            includedBlocks++;
            tailStart = block.startInclusive();
        }
        return tailStart;
    }

    private List<ChatMessage> pruneOldToolOutputs(List<ChatMessage> messages) {
        List<Integer> removableIndexes = new ArrayList<>();
        int retainedTokens = 0;
        int removableTokens = 0;
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            if (!(message instanceof ToolExecutionResultMessage)) {
                continue;
            }
            int tokens = tokenEstimator.estimateTokenCountInMessage(message);
            if (retainedTokens < retainedToolOutputTokens) {
                retainedTokens += tokens;
            } else {
                removableIndexes.add(index);
                removableTokens += tokens;
            }
        }
        if (removableTokens < toolOutputPruneMinimumTokens) {
            return messages;
        }
        List<ChatMessage> pruned = new ArrayList<>(messages);
        for (int index : removableIndexes) {
            ToolExecutionResultMessage result = (ToolExecutionResultMessage) messages.get(index);
            if (activeToolResultProjector != null) {
                pruned.set(index, activeToolResultProjector.archive(
                        result, result.hasSingleText() && result.text() != null
                                ? tokenEstimator.estimateTokenCountInText(result.text())
                                : tokenEstimator.estimateTokenCountInMessage(result)));
            } else {
                pruned.set(index, ToolExecutionResultMessage.from(
                        result.id(), result.toolName(),
                        "旧工具输出已从活动上下文裁剪，完整记录仍保存在任务 transcript 中；请依据已形成的调查状态继续，不要重复执行相同调用。"));
            }
        }
        return pruned;
    }

    private boolean hasIncompleteTrailingToolStep() {
        for (int index = transcript.size() - 1; index >= 0; index--) {
            ChatMessage message = transcript.get(index);
            if (message instanceof UserMessage || message instanceof AiMessage ai && !ai.hasToolExecutionRequests()) {
                return false;
            }
            if (!(message instanceof AiMessage aiMessage) || !aiMessage.hasToolExecutionRequests()) {
                continue;
            }
            Set<String> pending = new HashSet<>();
            aiMessage.toolExecutionRequests().forEach(request -> pending.add(request.id()));
            for (int resultIndex = index + 1; resultIndex < transcript.size(); resultIndex++) {
                if (transcript.get(resultIndex) instanceof ToolExecutionResultMessage result) {
                    pending.remove(result.id());
                }
            }
            return !pending.isEmpty();
        }
        return false;
    }

    private List<MessageBlock> messageBlocks(int startIndex) {
        List<MessageBlock> blocks = new ArrayList<>();
        int index = startIndex;
        while (index < transcript.size()) {
            int end = index + 1;
            ChatMessage message = transcript.get(index);
            if (message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                Set<String> pending = new HashSet<>();
                aiMessage.toolExecutionRequests().forEach(request -> pending.add(request.id()));
                while (end < transcript.size() && transcript.get(end) instanceof ToolExecutionResultMessage result) {
                    pending.remove(result.id());
                    end++;
                    if (pending.isEmpty()) {
                        break;
                    }
                }
            }
            blocks.add(new MessageBlock(index, end));
            index = end;
        }
        return blocks;
    }

    private LangChainCompactionStateStore.State normalizeState(LangChainCompactionStateStore.State loaded) {
        if (loaded.compactedMessageCount() <= 0 || loaded.compactedMessageCount() > transcript.size()
                || loaded.summary() == null || loaded.summary().isBlank()) {
            return LangChainCompactionStateStore.State.empty();
        }
        return loaded;
    }

    private int firstIndex(Class<? extends ChatMessage> type) {
        for (int index = 0; index < transcript.size(); index++) {
            if (type.isInstance(transcript.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private void ensureWithinHardLimit(List<ChatMessage> messages, LangChainContextBudget.Snapshot budget) {
        if (!budget.exceedsInputBudget() || hasIncompleteTrailingToolStep()) {
            return;
        }
        if (activeToolResultProjector != null) {
            List<ChatMessage> emergency = activeToolResultProjector.projectCurrentTurn(messages, true);
            if (!contextBudget.estimateMessages(emergency).exceedsInputBudget()) {
                return;
            }
        }
        throw new IllegalStateException("LangChain 活动上下文超过模型输入预算，且无法安全压缩；"
                + "messages=" + messages.size() + "，tokens=" + budget.estimatedInputTokens()
                + "，budget=" + inputTokenBudget);
    }

    private record MessageBlock(int startInclusive, int endExclusive) {
    }
}
