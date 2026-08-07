package top.fusb.lingxi.runtime.langchain.agent.model;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatObject;

class LangChainModelInputNormalizerTest {

    @Test
    void addsMissingResultsAndRemovesOrphanOrDuplicateResults() {
        ToolExecutionRequest completed = toolRequest("call-completed", "read_file");
        ToolExecutionRequest interrupted = toolRequest("call-interrupted", "grep");
        ToolExecutionResultMessage orphan = ToolExecutionResultMessage.from(
                "call-orphan", "write_file", "不应保留");
        ToolExecutionResultMessage completedResult = ToolExecutionResultMessage.from(completed, "读取成功");
        ChatRequest request = ChatRequest.builder()
                .messages(orphan, AiMessage.from(List.of(completed, interrupted)),
                        completedResult, completedResult, UserMessage.from("继续"))
                .build();

        ChatRequest normalized = new LangChainModelInputNormalizer(true, true).normalize(request);

        List<ToolExecutionResultMessage> results = normalized.messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .toList();
        assertThat(results).hasSize(2);
        assertThat(results).extracting(ToolExecutionResultMessage::id)
                .containsExactly("call-interrupted", "call-completed");
        assertThat(results.get(0).text()).contains("aborted", "缺少结果");
        assertThat(results.get(0).isError()).isTrue();
        assertThat(results.get(1).text()).isEqualTo("读取成功");
    }

    @Test
    void replacesUnsupportedImageAndAudioWithoutLosingMessageMetadata() {
        UserMessage user = UserMessage.builder()
                .name("operator")
                .attributes(Map.of("source", "upload"))
                .contents(List.of(TextContent.from("检查附件"),
                        ImageContent.from("aW1hZ2U=", "image/png"),
                        AudioContent.from("YXVkaW8=", "audio/wav")))
                .build();
        ToolExecutionRequest call = toolRequest("call-media", "inspect_media");
        ToolExecutionResultMessage result = ToolExecutionResultMessage.builder()
                .id(call.id())
                .toolName(call.name())
                .isError(false)
                .attributes(Map.of("trace", "trace-1"))
                .contents(List.of(ImageContent.from("aW1hZ2U=", "image/png"),
                        AudioContent.from("YXVkaW8=", "audio/wav")))
                .build();
        ChatRequest request = ChatRequest.builder()
                .messages(user, AiMessage.from(call), result)
                .build();

        ChatRequest normalized = new LangChainModelInputNormalizer(false, false).normalize(request);

        UserMessage normalizedUser = (UserMessage) normalized.messages().get(0);
        ToolExecutionResultMessage normalizedResult =
                (ToolExecutionResultMessage) normalized.messages().get(2);
        assertThat(normalizedUser.name()).isEqualTo("operator");
        assertThat(normalizedUser.attributes()).containsEntry("source", "upload");
        assertThat(normalizedResult.id()).isEqualTo(call.id());
        assertThat(normalizedResult.toolName()).isEqualTo(call.name());
        assertThat(normalizedResult.isError()).isFalse();
        assertThat(normalizedResult.attributes()).containsEntry("trace", "trace-1");
        assertThat(normalized.messages().stream()
                .flatMap(message -> contents(message).stream()))
                .noneMatch(content -> content instanceof ImageContent || content instanceof AudioContent);
        assertThat(normalized.messages().toString()).contains("当前模型不支持图片输入", "当前模型不支持音频输入");
    }

    @Test
    void returnsTheOriginalRequestWhenProtocolAndModalitiesAreAlreadyValid() {
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from(List.of(
                        TextContent.from("检查图片"),
                        ImageContent.from("aW1hZ2U=", "image/png"),
                        AudioContent.from("YXVkaW8=", "audio/wav"))))
                .build();

        ChatRequest normalized = new LangChainModelInputNormalizer(true, true).normalize(request);

        assertThatObject(normalized).isSameAs(request);
    }

    private ToolExecutionRequest toolRequest(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }

    private List<Content> contents(ChatMessage message) {
        if (message instanceof UserMessage userMessage) {
            return userMessage.contents();
        }
        if (message instanceof ToolExecutionResultMessage result) {
            return result.contents();
        }
        return List.of();
    }
}
