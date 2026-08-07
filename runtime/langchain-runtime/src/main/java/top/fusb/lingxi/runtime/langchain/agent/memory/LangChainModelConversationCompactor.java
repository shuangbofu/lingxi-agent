package top.fusb.lingxi.runtime.langchain.agent.memory;

import top.fusb.lingxi.runtime.langchain.util.LangChainPromptKit;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;

public final class LangChainModelConversationCompactor implements LangChainConversationCompactor {

    private final ChatModel model;

    public LangChainModelConversationCompactor(ChatModel model) {
        this.model = model;
    }

    @Override
    public String compact(ChatMessage originalTask, String previousSummary, List<ChatMessage> messages) {
        String input = LangChainPromptKit.format("langchain-compaction-user.md",
                ChatMessageSerializer.messageToJson(originalTask),
                previousSummary == null || previousSummary.isBlank() ? "无" : previousSummary,
                ChatMessageSerializer.messagesToJson(messages));
        ChatResponse response = model.chat(List.of(
                SystemMessage.from(LangChainPromptKit.load("langchain-compaction-system.md")),
                UserMessage.from(input)
        ));
        String summary = response.aiMessage() == null ? null : response.aiMessage().text();
        if (summary == null || summary.isBlank()) {
            throw new IllegalStateException("上下文压缩模型返回空结果");
        }
        return summary.strip();
    }
}
