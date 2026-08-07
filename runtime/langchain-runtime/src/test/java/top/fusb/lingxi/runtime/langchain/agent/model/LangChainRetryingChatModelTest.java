package top.fusb.lingxi.runtime.langchain.agent.model;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangChainRetryingChatModelTest {

    @Test
    void retriesRecoverableCompactionCallWithoutChangingTheRequest() {
        AtomicInteger attempts = new AtomicInteger();
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("compact")).build();
        ChatModel delegate = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest actual) {
                assertThat(actual).isSameAs(request);
                if (attempts.incrementAndGet() == 1) {
                    throw new TimeoutException("timeout");
                }
                return ChatResponse.builder().aiMessage(AiMessage.from("summary")).build();
            }
        };

        ChatResponse response = model(delegate).doChat(request);

        assertThat(response.aiMessage().text()).isEqualTo("summary");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void propagatesNonRetriableCompactionFailureImmediately() {
        AtomicInteger attempts = new AtomicInteger();
        ChatModel delegate = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                attempts.incrementAndGet();
                throw new AuthenticationException("bad key");
            }
        };

        assertThatThrownBy(() -> model(delegate).doChat(
                ChatRequest.builder().messages(UserMessage.from("compact")).build()))
                .isInstanceOf(AuthenticationException.class);
        assertThat(attempts).hasValue(1);
    }

    private LangChainRetryingChatModel model(ChatModel delegate) {
        return new LangChainRetryingChatModel(delegate,
                new LangChainModelCallPolicy(3, Duration.ZERO, Duration.ZERO));
    }
}
