package top.fusb.lingxi.runtime.langchain.agent.model;

import top.fusb.lingxi.runtime.langchain.agent.LangChainExecutionContext;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 只重试当前 ChatRequest 的流式模型包装器，不重放 Agent Turn 或工具调用。
 */
@Slf4j
public final class LangChainRetryingStreamingChatModel implements StreamingChatModel {

    private final StreamingChatModel delegate;
    private final LangChainModelCallPolicy policy;
    private final LangChainExecutionContext context;

    public LangChainRetryingStreamingChatModel(StreamingChatModel delegate,
                                               LangChainModelCallPolicy policy,
                                               LangChainExecutionContext context) {
        this.delegate = delegate;
        this.policy = policy;
        this.context = context;
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        new RetryingCall(request, handler).start(1);
    }

    private final class RetryingCall {

        private final ChatRequest request;
        private final StreamingChatResponseHandler handler;
        private final AtomicBoolean completed = new AtomicBoolean();

        private RetryingCall(ChatRequest request, StreamingChatResponseHandler handler) {
            this.request = request;
            this.handler = handler;
        }

        private void start(int attempt) {
            if (completed.get() || context.isCancelled()) {
                return;
            }
            AttemptHandler attemptHandler = new AttemptHandler(attempt);
            try {
                delegate.doChat(request, attemptHandler);
            } catch (RuntimeException exception) {
                attemptHandler.onError(exception);
            }
        }

        private final class AttemptHandler implements StreamingChatResponseHandler {

            private final int attempt;
            private final AtomicBoolean terminal = new AtomicBoolean();
            private volatile boolean partialOutputStarted;

            private AttemptHandler(int attempt) {
                this.attempt = attempt;
            }

            @Override
            public void onPartialResponse(String partialResponse) {
                forwardPartial(() -> handler.onPartialResponse(partialResponse));
            }

            @Override
            public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext partialContext) {
                forwardPartial(() -> handler.onPartialResponse(partialResponse, partialContext));
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                forwardPartial(() -> handler.onPartialThinking(partialThinking));
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext partialContext) {
                forwardPartial(() -> handler.onPartialThinking(partialThinking, partialContext));
            }

            @Override
            public void onPartialToolCall(PartialToolCall partialToolCall) {
                forwardPartial(() -> handler.onPartialToolCall(partialToolCall));
            }

            @Override
            public void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext partialContext) {
                forwardPartial(() -> handler.onPartialToolCall(partialToolCall, partialContext));
            }

            @Override
            public void onCompleteToolCall(CompleteToolCall completeToolCall) {
                forwardPartial(() -> handler.onCompleteToolCall(completeToolCall));
            }

            @Override
            public void onUnmappedRawEvent(Object event) {
                if (!terminal.get() && !completed.get()) {
                    handler.onUnmappedRawEvent(event);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                if (terminal.compareAndSet(false, true) && completed.compareAndSet(false, true)) {
                    handler.onCompleteResponse(response);
                }
            }

            @Override
            public void onError(Throwable error) {
                if (!terminal.compareAndSet(false, true) || completed.get() || context.isCancelled()) {
                    return;
                }
                LangChainModelCallPolicy.Decision decision =
                        policy.evaluate(error, attempt, partialOutputStarted);
                if (!decision.shouldRetry()) {
                    if (completed.compareAndSet(false, true)) {
                        handler.onError(error);
                    }
                    return;
                }
                Duration delay = decision.backoff();
                log.warn("LangChain model request failed and will retry attempt={} nextAttempt={} category={} "
                                + "delayMs={} error={}",
                        attempt, attempt + 1, decision.category(), delay.toMillis(), error.toString());
                CompletableFuture.delayedExecutor(delay.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                        .execute(() -> start(attempt + 1));
            }

            private void forwardPartial(Runnable callback) {
                if (terminal.get() || completed.get()) {
                    return;
                }
                partialOutputStarted = true;
                callback.run();
            }
        }
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
