package top.fusb.lingxi.runtime.langchain.agent.model;

import top.fusb.lingxi.runtime.langchain.agent.memory.LangChainTokenCountEstimator;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.List;

/**
 * 统一估算模型请求、上下文压缩和活动指标使用的输入 Token 预算。
 */
public final class LangChainContextBudget {

    private final TokenCountEstimator tokenEstimator;
    private final String taskInstructions;
    private final String mcpInstructions;
    private final int inputTokenBudget;
    private final int compactionTriggerTokens;
    private volatile List<ToolSpecification> reservedToolSpecifications = List.of();

    /**
     * 创建一次执行共享的上下文预算。
     *
     * @param tokenEstimator 当前模型对应的 Token 估算器
     * @param inputTokenBudget 模型请求允许使用的最大输入 Token
     * @param compactionTriggerRatio 上下文压缩相对输入预算的触发比例
     * @param taskInstructions 用于指标分类的任务指令
     * @param mcpInstructions 用于指标分类的 MCP 指令
     * @throws IllegalArgumentException 输入预算或压缩比例无效时抛出
     */
    public LangChainContextBudget(TokenCountEstimator tokenEstimator,
                                  int inputTokenBudget,
                                  double compactionTriggerRatio,
                                  String taskInstructions,
                                  String mcpInstructions) {
        if (tokenEstimator == null || inputTokenBudget < 1 || compactionTriggerRatio <= 0D
                || compactionTriggerRatio > 1D) {
            throw new IllegalArgumentException("LangChain 上下文预算参数无效");
        }
        this.tokenEstimator = tokenEstimator;
        this.inputTokenBudget = inputTokenBudget;
        this.compactionTriggerTokens = Math.max(1, (int) (inputTokenBudget * compactionTriggerRatio));
        this.taskInstructions = taskInstructions == null ? "" : taskInstructions;
        this.mcpInstructions = mcpInstructions == null ? "" : mcpInstructions;
    }

    /**
     * 更新下一次 Memory 投影需要预留的工具 Schema。
     *
     * @param toolSpecifications 当前模型请求实际可见的工具定义
     * @return 无返回值
     */
    public void reserveToolSpecifications(List<ToolSpecification> toolSpecifications) {
        reservedToolSpecifications = toolSpecifications == null ? List.of() : List.copyOf(toolSpecifications);
    }

    /**
     * 估算真实模型请求，工具 Schema 以该请求携带的定义为准。
     *
     * @param request 待发送的模型请求
     * @return 包含总量、分类、预算和剩余量的快照
     */
    public Snapshot estimate(ChatRequest request) {
        if (request == null) {
            return Snapshot.empty(inputTokenBudget, compactionTriggerTokens);
        }
        return estimate(request.messages(), request.toolSpecifications());
    }

    /**
     * 估算 Memory 当前消息，并计入已预留的工具 Schema。
     *
     * @param messages Memory 即将交给模型的活动消息
     * @return 包含总量、分类、预算和剩余量的快照
     */
    public Snapshot estimateMessages(Iterable<ChatMessage> messages) {
        return estimate(messages, reservedToolSpecifications);
    }

    public TokenCountEstimator tokenEstimator() {
        return tokenEstimator;
    }

    public int inputTokenBudget() {
        return inputTokenBudget;
    }

    public int compactionTriggerTokens() {
        return compactionTriggerTokens;
    }

    private Snapshot estimate(Iterable<ChatMessage> sourceMessages,
                              List<ToolSpecification> toolSpecifications) {
        List<ChatMessage> messages = sourceMessages == null ? List.of() : copy(sourceMessages);
        long estimatedMessageTokens = tokenEstimator.estimateTokenCountInMessages(messages);
        long systemMessageTokens = 0L;
        long conversationTokens = 0L;
        long toolResultTokens = 0L;
        long imageTokens = 0L;
        for (ChatMessage message : messages) {
            long messageTokens = tokenEstimator.estimateTokenCountInMessage(message);
            long messageImageTokens = imageTokens(message);
            imageTokens += messageImageTokens;
            long textTokens = Math.max(0L, messageTokens - messageImageTokens);
            if (message instanceof SystemMessage) {
                systemMessageTokens += textTokens;
            } else if (message instanceof ToolExecutionResultMessage) {
                toolResultTokens += textTokens;
            } else {
                conversationTokens += textTokens;
            }
        }
        long classifiedMessageTokens = systemMessageTokens + conversationTokens + toolResultTokens + imageTokens;
        long envelopeTokens = Math.max(0L, estimatedMessageTokens - classifiedMessageTokens);
        if (systemMessageTokens > 0L) {
            systemMessageTokens += envelopeTokens;
        } else {
            conversationTokens += envelopeTokens;
        }
        long mcpTokens = Math.min(systemMessageTokens, estimateText(mcpInstructions));
        long taskTokens = Math.min(Math.max(0L, systemMessageTokens - mcpTokens), estimateText(taskInstructions));
        long baseSystemTokens = Math.max(0L, systemMessageTokens - taskTokens - mcpTokens);
        long toolSchemaTokens = toolSpecifications == null || toolSpecifications.isEmpty()
                ? 0L : estimateText(toolSpecifications.toString());
        long estimatedInputTokens = saturatedAdd(estimatedMessageTokens, toolSchemaTokens);
        return new Snapshot(baseSystemTokens, taskTokens, mcpTokens, toolSchemaTokens,
                conversationTokens, toolResultTokens, imageTokens, estimatedInputTokens,
                inputTokenBudget, compactionTriggerTokens);
    }

    private List<ChatMessage> copy(Iterable<ChatMessage> messages) {
        java.util.ArrayList<ChatMessage> copy = new java.util.ArrayList<>();
        messages.forEach(copy::add);
        return List.copyOf(copy);
    }

    private long imageTokens(ChatMessage message) {
        return tokenEstimator instanceof LangChainTokenCountEstimator estimator
                ? estimator.estimateImageTokenCountInMessage(message) : 0L;
    }

    private long estimateText(String text) {
        return text == null || text.isBlank() ? 0L : tokenEstimator.estimateTokenCountInText(text);
    }

    private long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    public record Snapshot(long systemInstructionTokens,
                           long taskInstructionTokens,
                           long mcpInstructionTokens,
                           long toolSchemaTokens,
                           long conversationTokens,
                           long toolResultTokens,
                           long imageTokens,
                           long estimatedInputTokens,
                           int inputTokenBudget,
                           int compactionTriggerTokens) {

        static Snapshot empty(int inputTokenBudget, int compactionTriggerTokens) {
            return new Snapshot(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                    inputTokenBudget, compactionTriggerTokens);
        }

        public long remainingTokens() {
            return Math.max(0L, (long) inputTokenBudget - estimatedInputTokens);
        }

        public boolean exceedsInputBudget() {
            return estimatedInputTokens > inputTokenBudget;
        }

        public boolean shouldCompact() {
            return estimatedInputTokens > compactionTriggerTokens;
        }
    }
}
