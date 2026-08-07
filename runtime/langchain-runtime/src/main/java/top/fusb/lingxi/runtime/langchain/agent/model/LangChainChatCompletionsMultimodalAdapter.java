package top.fusb.lingxi.runtime.langchain.agent.model;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 将工具返回的图片转换为 Chat Completions 协议允许的用户多模态消息。
 */
public final class LangChainChatCompletionsMultimodalAdapter implements StreamingChatModel {

    private final StreamingChatModel delegate;

    public LangChainChatCompletionsMultimodalAdapter(StreamingChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        delegate.doChat(adaptRequest(request), handler);
    }

    /**
     * 保留工具结果消息的调用关联，将其中图片移动到该批工具结果之后的用户消息中。
     *
     * @param request LangChain4j 生成的原始模型请求
     * @return 符合 Chat Completions 消息约束的模型请求
     * @throws IllegalArgumentException 工具结果包含图片和文本之外的内容时抛出
     */
    static ChatRequest adaptRequest(ChatRequest request) {
        List<ChatMessage> messages = new ArrayList<>();
        List<ImageContent> pendingImages = new ArrayList<>();
        boolean changed = false;
        for (ChatMessage message : request.messages()) {
            if (message instanceof ToolExecutionResultMessage result) {
                if (result.hasSingleText()) {
                    messages.add(result);
                    continue;
                }
                List<String> textParts = new ArrayList<>();
                int imageCount = 0;
                for (Content content : result.contents()) {
                    if (content instanceof TextContent textContent) {
                        if (!textContent.text().isBlank()) {
                            textParts.add(textContent.text());
                        }
                    } else if (content instanceof ImageContent imageContent) {
                        pendingImages.add(imageContent);
                        imageCount++;
                    } else {
                        throw new IllegalArgumentException(
                                "Chat Completions 不支持该工具结果内容类型：" + content.type());
                    }
                }
                String text = String.join("\n", textParts);
                if (imageCount > 0) {
                    text = text.isBlank()
                            ? "工具已返回 " + imageCount + " 张图片，图片内容附在下一条用户消息中。"
                            : text + "\n工具另返回 " + imageCount + " 张图片，图片内容附在下一条用户消息中。";
                }
                messages.add(ToolExecutionResultMessage.builder()
                        .id(result.id())
                        .toolName(result.toolName())
                        .text(text.isBlank() ? "工具执行完成。" : text)
                        .isError(result.isError())
                        .attributes(result.attributes())
                        .build());
                changed = true;
                continue;
            }
            appendPendingImages(messages, pendingImages);
            messages.add(message);
        }
        appendPendingImages(messages, pendingImages);
        return changed ? request.toBuilder().messages(messages).build() : request;
    }

    private static void appendPendingImages(List<ChatMessage> messages, List<ImageContent> pendingImages) {
        if (pendingImages.isEmpty()) {
            return;
        }
        List<Content> contents = new ArrayList<>(pendingImages.size() + 1);
        contents.add(TextContent.from("以下图片来自刚完成的工具调用，请按原工具调用要求检查。"));
        contents.addAll(pendingImages);
        messages.add(UserMessage.from(contents));
        pendingImages.clear();
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return delegate.listeners();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }
}
