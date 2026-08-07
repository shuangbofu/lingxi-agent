package top.fusb.lingxi.runtime.langchain.agent.memory;

import top.fusb.lingxi.runtime.langchain.agent.evidence.LangChainEvidenceStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
public final class LangChainActiveToolResultProjector {

    private static final String PLACEHOLDER_KIND = "langchain.active_tool_result_evidence";
    private final Object memoryId;
    private final TokenCountEstimator tokenEstimator;
    private final LangChainEvidenceStore evidenceStore;
    private final int maximumResultTokens;
    private final Map<String, ArchivedResult> archivedResults = new LinkedHashMap<>();

    public LangChainActiveToolResultProjector(Object memoryId,
                                              TokenCountEstimator tokenEstimator,
                                              LangChainEvidenceStore evidenceStore,
                                              int maximumResultTokens) {
        if (tokenEstimator == null || evidenceStore == null || maximumResultTokens < 1) {
            throw new IllegalArgumentException("活动工具结果投影参数无效");
        }
        this.memoryId = memoryId;
        this.tokenEstimator = tokenEstimator;
        this.evidenceStore = evidenceStore;
        this.maximumResultTokens = maximumResultTokens;
    }

    /**
     * 投影当前用户轮次内已经完成的工具步骤，默认保留最新步骤原文供模型消费一次。
     *
     * @param messages 当前准备发送给模型的消息
     * @param includeNewestStep 容量紧急恢复时是否允许归档最新完整工具步骤
     * @return 仅替换超大工具结果正文且保持调用配对关系的请求消息副本
     */
    public List<ChatMessage> projectCurrentTurn(List<ChatMessage> messages, boolean includeNewestStep) {
        List<ToolStep> completedSteps = completedCurrentTurnSteps(messages);
        int eligibleSteps = includeNewestStep ? completedSteps.size() : Math.max(0, completedSteps.size() - 1);
        if (eligibleSteps == 0) {
            return messages;
        }
        List<ChatMessage> projected = new ArrayList<>(messages);
        int rewritten = 0;
        long savedTokens = 0L;
        for (int stepIndex = 0; stepIndex < eligibleSteps; stepIndex++) {
            for (int resultIndex : completedSteps.get(stepIndex).resultIndexes()) {
                ToolExecutionResultMessage result = (ToolExecutionResultMessage) messages.get(resultIndex);
                if (!result.hasSingleText() || result.text() == null) {
                    continue;
                }
                int originalTokens = tokenEstimator.estimateTokenCountInText(result.text());
                if (originalTokens <= maximumResultTokens) {
                    continue;
                }
                ToolExecutionResultMessage replacement = archive(result, originalTokens);
                if (replacement != result) {
                    projected.set(resultIndex, replacement);
                    rewritten++;
                    savedTokens += Math.max(0,
                            originalTokens - tokenEstimator.estimateTokenCountInMessage(replacement));
                }
            }
        }
        if (rewritten > 0) {
            log.info("LangChain active tool results projected memoryId={} rewritten={} estimatedTokensSaved={} emergency={}",
                    memoryId, rewritten, savedTokens, includeNewestStep);
            return projected;
        }
        return messages;
    }

    /**
     * 将一条文本工具结果归档为可读回占位符，保留错误标记和消息属性。
     *
     * @param result 原始工具结果消息
     * @param originalTokens 原始消息估算 Token 数
     * @return 归档成功时返回占位符消息，失败或非单文本结果时返回原消息
     */
    public ToolExecutionResultMessage archive(ToolExecutionResultMessage result, int originalTokens) {
        if (result == null || result.id() == null || result.id().isBlank()
                || !result.hasSingleText() || result.text() == null
                || result.text().startsWith("{\"kind\":\"" + PLACEHOLDER_KIND + "\"")) {
            return result;
        }
        String bodySha256 = LangChainEvidenceStore.sha256(result.text());
        String cacheKey = result.id() + ":" + bodySha256;
        try {
            ArchivedResult archived = archivedResults.get(cacheKey);
            if (archived == null) {
                LangChainEvidenceStore.ResolvedEvidence resolved = evidenceStore
                        .resolveAnnotatedReference(result.text()).orElse(null);
                LangChainEvidenceStore.EvidenceReference reference;
                String evidenceContent;
                int evidenceTokens;
                if (resolved == null) {
                    reference = evidenceStore.record(
                            result.toolName(), "toolCallId=" + result.id(), result.text());
                    evidenceContent = result.text();
                    evidenceTokens = originalTokens;
                } else {
                    reference = resolved.reference();
                    evidenceContent = resolved.content();
                    evidenceTokens = tokenEstimator.estimateTokenCountInText(evidenceContent);
                }
                String placeholder = evidenceStore.activeResultPlaceholder(
                        reference, result.id(), result.toolName(), evidenceTokens, evidenceContent).toString();
                archived = new ArchivedResult(placeholder);
                archivedResults.put(cacheKey, archived);
            }
            return result.toBuilder().contents(TextContent.from(archived.placeholder())).build();
        } catch (RuntimeException exception) {
            log.warn("LangChain active tool result archive failed memoryId={} tool={} toolCallId={}",
                    memoryId, result.toolName(), result.id(), exception);
            return result;
        }
    }

    private List<ToolStep> completedCurrentTurnSteps(List<ChatMessage> messages) {
        int turnStart = 0;
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof UserMessage) {
                turnStart = index + 1;
                break;
            }
        }
        List<ToolStep> steps = new ArrayList<>();
        for (int index = turnStart; index < messages.size(); index++) {
            if (!(messages.get(index) instanceof AiMessage aiMessage) || !aiMessage.hasToolExecutionRequests()) {
                continue;
            }
            Set<String> pending = new HashSet<>();
            aiMessage.toolExecutionRequests().forEach(request -> pending.add(request.id()));
            List<Integer> resultIndexes = new ArrayList<>();
            int resultIndex = index + 1;
            while (resultIndex < messages.size()
                    && messages.get(resultIndex) instanceof ToolExecutionResultMessage result) {
                if (pending.remove(result.id())) {
                    resultIndexes.add(resultIndex);
                }
                resultIndex++;
            }
            if (pending.isEmpty() && !resultIndexes.isEmpty()) {
                steps.add(new ToolStep(List.copyOf(resultIndexes)));
            }
            index = resultIndex - 1;
        }
        return steps;
    }

    private record ToolStep(List<Integer> resultIndexes) {
    }

    private record ArchivedResult(String placeholder) {
    }
}
