package top.fusb.lingxi.runtime.langchain.agent.model;

import top.fusb.lingxi.runtime.api.event.RuntimeEvent;
import top.fusb.lingxi.runtime.api.event.RuntimeEventListener;
import top.fusb.lingxi.runtime.langchain.agent.LangChainExecutionContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LangChainRetryingStreamingChatModelTest {

    @Test
    void retriesTheSameRequestAfterRecoverableFailureBeforeOutput() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        List<ChatRequest> received = new ArrayList<>();
        StreamingChatModel rawModel = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                received.add(request);
                if (attempts.incrementAndGet() == 1) {
                    handler.onError(new RateLimitException("limited"));
                    return;
                }
                handler.onCompleteResponse(response("ok"));
            }
        };
        LangChainExecutionContext context = context();
        LangChainRetryingStreamingChatModel model = model(rawModel, context, 3);
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("test")).build();
        ResultHandler result = new ResultHandler();

        model.doChat(request, result);

        assertThat(result.completed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(result.response.get().aiMessage().text()).isEqualTo("ok");
        assertThat(result.error.get()).isNull();
        assertThat(received).containsExactly(request, request);
        assertThat(context.usage().requestCount()).isEqualTo(2L);
        assertThat(context.usage().totalTokens()).isEqualTo(15L);
    }

    @Test
    void doesNotRetryAfterAnyPartialOutput() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        StreamingChatModel rawModel = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                attempts.incrementAndGet();
                handler.onPartialResponse("partial");
                handler.onError(new TimeoutException("disconnected"));
            }
        };
        LangChainExecutionContext context = context();
        ResultHandler result = new ResultHandler();

        model(rawModel, context, 3).doChat(
                ChatRequest.builder().messages(UserMessage.from("test")).build(), result);

        assertThat(result.completed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(result.partials).containsExactly("partial");
        assertThat(result.error.get()).isInstanceOf(TimeoutException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void stopsAtAttemptLimitAndNeverRetriesNonRetriableErrors() throws Exception {
        AtomicInteger transientAttempts = new AtomicInteger();
        StreamingChatModel transientModel = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                transientAttempts.incrementAndGet();
                handler.onError(new RateLimitException("limited"));
            }
        };
        ResultHandler exhausted = new ResultHandler();
        model(transientModel, context(), 3).doChat(
                ChatRequest.builder().messages(UserMessage.from("test")).build(), exhausted);

        assertThat(exhausted.completed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(exhausted.error.get()).isInstanceOf(RateLimitException.class);
        assertThat(transientAttempts).hasValue(3);

        AtomicInteger authAttempts = new AtomicInteger();
        StreamingChatModel authModel = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                authAttempts.incrementAndGet();
                throw new AuthenticationException("bad key");
            }
        };
        ResultHandler rejected = new ResultHandler();
        model(authModel, context(), 3).doChat(
                ChatRequest.builder().messages(UserMessage.from("test")).build(), rejected);

        assertThat(rejected.completed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(rejected.error.get()).isInstanceOf(AuthenticationException.class);
        assertThat(authAttempts).hasValue(1);
    }

    private LangChainRetryingStreamingChatModel model(StreamingChatModel rawModel,
                                                       LangChainExecutionContext context,
                                                       int maxAttempts) {
        return new LangChainRetryingStreamingChatModel(
                new LangChainGuardedStreamingChatModel(rawModel, context),
                new LangChainModelCallPolicy(maxAttempts, Duration.ZERO, Duration.ZERO), context);
    }

    private ChatResponse response(String text) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .tokenUsage(new TokenUsage(10, 5, 15))
                .build();
    }

    private LangChainExecutionContext context() {
        return new LangChainExecutionContext("retry", Path.of("."), new RuntimeEventListener() {
            @Override
            public void onEvent(RuntimeEvent event) {
            }
        }, null, 10, 2, 10_000L);
    }

    private static final class ResultHandler implements StreamingChatResponseHandler {

        private final CountDownLatch completed = new CountDownLatch(1);
        private final AtomicReference<ChatResponse> response = new AtomicReference<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final List<String> partials = new ArrayList<>();

        @Override
        public void onPartialResponse(String partialResponse) {
            partials.add(partialResponse);
        }

        @Override
        public void onCompleteResponse(ChatResponse response) {
            this.response.set(response);
            completed.countDown();
        }

        @Override
        public void onError(Throwable error) {
            this.error.set(error);
            completed.countDown();
        }
    }
}
