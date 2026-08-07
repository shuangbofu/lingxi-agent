package top.fusb.lingxi.runtime.langchain.agent.memory;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LangChainTokenCountEstimatorTest {

    @Test
    void estimatesMultimodalToolResultsWithoutConvertingThemToText() {
        LangChainTokenCountEstimator estimator = new LangChainTokenCountEstimator("gpt-5", 3_072);
        ToolExecutionResultMessage result = ToolExecutionResultMessage.builder()
                .id("call-image")
                .toolName("read_file")
                .contents(List.of(
                        ImageContent.from("AQID", "image/jpeg", ImageContent.DetailLevel.HIGH),
                        ImageContent.from("BAUG", "image/png", ImageContent.DetailLevel.HIGH)))
                .build();

        int tokens = estimator.estimateTokenCountInMessage(result);

        assertThat(tokens).isEqualTo(4 + 2 * 3_072);
    }

    @Test
    void includesTextAndImageContentsInConversationEstimate() {
        LangChainTokenCountEstimator estimator = new LangChainTokenCountEstimator("custom-compatible-model", 2_048);
        UserMessage message = UserMessage.from(List.of(
                TextContent.from("检查画面"),
                ImageContent.from("AQID", "image/jpeg", ImageContent.DetailLevel.HIGH)));

        assertThat(estimator.estimateTokenCountInMessage(message)).isGreaterThan(2_048);
        assertThat(estimator.estimateTokenCountInMessages(List.of(message)))
                .isEqualTo(3 + estimator.estimateTokenCountInMessage(message));
    }
}
