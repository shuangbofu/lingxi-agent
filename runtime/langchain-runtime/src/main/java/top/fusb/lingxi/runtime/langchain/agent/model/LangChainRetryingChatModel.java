package top.fusb.lingxi.runtime.langchain.agent.model;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;

/**
 * 为没有流式输出和工具副作用的压缩模型提供同一请求级重试策略。
 */
@Slf4j
public final class LangChainRetryingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final LangChainModelCallPolicy policy;

    public LangChainRetryingChatModel(ChatModel delegate, LangChainModelCallPolicy policy) {
        this.delegate = delegate;
        this.policy = policy;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        int attempt = 1;
        while (true) {
            try {
                return delegate.doChat(request);
            } catch (RuntimeException exception) {
                LangChainModelCallPolicy.Decision decision = policy.evaluate(exception, attempt, false);
                if (!decision.shouldRetry()) {
                    throw exception;
                }
                log.warn("LangChain compact model request failed and will retry attempt={} nextAttempt={} "
                                + "category={} delayMs={} error={}",
                        attempt, attempt + 1, decision.category(), decision.backoff().toMillis(),
                        exception.toString());
                try {
                    Thread.sleep(decision.backoff().toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("LangChain 压缩模型重试等待被中断", interrupted);
                }
                attempt++;
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
