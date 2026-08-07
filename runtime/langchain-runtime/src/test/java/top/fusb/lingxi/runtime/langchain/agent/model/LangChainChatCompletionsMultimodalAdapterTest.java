package top.fusb.lingxi.runtime.langchain.agent.model;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LangChainChatCompletionsMultimodalAdapterTest {

    @Test
    void movesToolResultImagesIntoFollowingUserMessage() {
        ImageContent first = ImageContent.from("Zmlyc3Q=", "image/png");
        ImageContent second = ImageContent.from("c2Vjb25k", "image/jpeg");
        ToolExecutionResultMessage result = ToolExecutionResultMessage.builder()
                .id("tool-1")
                .toolName("read_file")
                .contents(List.of(first, second))
                .build();

        ChatRequest adapted = LangChainChatCompletionsMultimodalAdapter.adaptRequest(
                ChatRequest.builder().messages(result).build());

        assertThat(adapted.messages()).hasSize(2);
        assertThat(adapted.messages().get(0))
                .isInstanceOfSatisfying(ToolExecutionResultMessage.class, message -> {
                    assertThat(message.id()).isEqualTo("tool-1");
                    assertThat(message.toolName()).isEqualTo("read_file");
                    assertThat(message.hasSingleText()).isTrue();
                    assertThat(message.text()).contains("2 张图片");
                });
        assertThat(adapted.messages().get(1))
                .isInstanceOfSatisfying(UserMessage.class, message -> {
                    assertThat(message.contents()).hasSize(3);
                    assertThat(message.contents().get(0)).isInstanceOf(TextContent.class);
                    assertThat(message.contents().subList(1, 3)).containsExactly(first, second);
                });
    }

    @Test
    void leavesOrdinaryTextToolResultsUnchanged() {
        ToolExecutionResultMessage result = ToolExecutionResultMessage.from(
                "tool-1", "read_file", "文件内容");
        ChatRequest request = ChatRequest.builder().messages(result).build();

        ChatRequest adapted = LangChainChatCompletionsMultimodalAdapter.adaptRequest(request);

        assertThat(adapted).isSameAs(request);
    }

    @Test
    void appendsImagesAfterAllParallelToolResults() {
        ToolExecutionResultMessage imageResult = ToolExecutionResultMessage.builder()
                .id("image-tool")
                .toolName("read_file")
                .contents(ImageContent.from("aW1hZ2U=", "image/png"))
                .build();
        ToolExecutionResultMessage textResult = ToolExecutionResultMessage.from(
                "text-tool", "read_file", "文件内容");

        ChatRequest adapted = LangChainChatCompletionsMultimodalAdapter.adaptRequest(
                ChatRequest.builder().messages(imageResult, textResult).build());

        assertThat(adapted.messages()).hasSize(3);
        assertThat(adapted.messages().subList(0, 2))
                .allMatch(ToolExecutionResultMessage.class::isInstance);
        assertThat(adapted.messages().get(2)).isInstanceOf(UserMessage.class);
    }
}
