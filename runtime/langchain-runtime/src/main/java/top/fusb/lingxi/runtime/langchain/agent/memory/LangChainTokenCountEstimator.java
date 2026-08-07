package top.fusb.lingxi.runtime.langchain.agent.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class LangChainTokenCountEstimator implements TokenCountEstimator {

    private static final int MESSAGE_OVERHEAD_TOKENS = 4;
    private static final int UNKNOWN_CONTENT_TOKENS = 512;

    private final OpenAiTokenCountEstimator textEstimator;
    private final int imageTokenEstimate;

    /**
     * 创建兼容 OpenAI 文本消息和多模态工具结果的 Token 估算器。
     *
     * @param modelName 当前主模型名称，未知兼容模型使用 GPT-5 编码近似估算
     * @param imageTokenEstimate 每张图片计入上下文窗口的保守 Token 数
     * @throws IllegalArgumentException 图片 Token 估算值小于 1 时抛出
     */
    public LangChainTokenCountEstimator(String modelName, int imageTokenEstimate) {
        if (imageTokenEstimate < 1) {
            throw new IllegalArgumentException("图片 Token 估算值必须大于 0");
        }
        String normalizedModel = modelName == null ? "" : modelName.trim();
        String estimatorModel = normalizedModel.startsWith("gpt-") || normalizedModel.startsWith("o")
                ? normalizedModel : "gpt-5";
        if (!estimatorModel.equals(normalizedModel)) {
            log.info("LangChain token estimator uses compatible encoding model={} configuredModel={}",
                    estimatorModel, normalizedModel);
        }
        this.textEstimator = new OpenAiTokenCountEstimator(estimatorModel);
        this.imageTokenEstimate = imageTokenEstimate;
    }

    @Override
    public int estimateTokenCountInText(String text) {
        return text == null || text.isEmpty() ? 0 : textEstimator.estimateTokenCountInText(text);
    }

    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
        if (message instanceof UserMessage userMessage) {
            return MESSAGE_OVERHEAD_TOKENS + estimateContents(userMessage.contents());
        }
        if (message instanceof ToolExecutionResultMessage resultMessage) {
            return MESSAGE_OVERHEAD_TOKENS + estimateContents(resultMessage.contents());
        }
        return textEstimator.estimateTokenCountInMessage(message);
    }

    @Override
    public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
        int tokens = 3;
        for (ChatMessage message : messages) {
            tokens += estimateTokenCountInMessage(message);
        }
        return tokens;
    }

    /**
     * 汇总一个多模态消息中所有内容块的近似 Token 数。
     *
     * @param contents 文本、图片或其他内容块
     * @return 该组内容占用的近似 Token 数
     */
    private int estimateContents(Iterable<Content> contents) {
        int tokens = 0;
        for (Content content : contents) {
            if (content instanceof TextContent textContent) {
                tokens += estimateTokenCountInText(textContent.text());
            } else if (content instanceof ImageContent) {
                tokens += imageTokenEstimate;
            } else {
                tokens += UNKNOWN_CONTENT_TOKENS;
            }
        }
        return tokens;
    }

    /**
     * 估算消息中图片内容块占用的 Token，用于从文本历史和工具结果中拆出图片输入。
     *
     * @param message 待分析的聊天消息
     * @return 消息中图片内容块的估算 Token 数
     */
    public int estimateImageTokenCountInMessage(ChatMessage message) {
        Iterable<Content> contents;
        if (message instanceof UserMessage userMessage) {
            contents = userMessage.contents();
        } else if (message instanceof ToolExecutionResultMessage resultMessage) {
            contents = resultMessage.contents();
        } else {
            return 0;
        }
        int tokens = 0;
        for (Content content : contents) {
            if (content instanceof ImageContent) {
                tokens += imageTokenEstimate;
            }
        }
        return tokens;
    }
}
