package top.fusb.lingxi.runtime.langchain.agent.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LangChainModelConversationCompactorTest {

    @Test
    void assemblesCompactionMessagesFromPromptResources() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                captured.set(request);
                return ChatResponse.builder().aiMessage(AiMessage.from("压缩结果")).build();
            }
        };

        String result = new LangChainModelConversationCompactor(model).compact(
                UserMessage.from("核查实现"), "已有事实", List.of(AiMessage.from("新增证据")));

        assertThat(result).isEqualTo("压缩结果");
        assertThat(captured.get().messages()).hasSize(2);
        assertThat(((SystemMessage) captured.get().messages().get(0)).text())
                .startsWith("你负责压缩 Agent 已完成的调查过程")
                .contains("## 当前目标", "## 下一步");
        assertThat(((UserMessage) captured.get().messages().get(1)).singleText())
                .contains("原始任务：", "核查实现")
                .contains("已有压缩状态：\n已有事实")
                .contains("本次新增调查记录：", "新增证据");
    }
}
