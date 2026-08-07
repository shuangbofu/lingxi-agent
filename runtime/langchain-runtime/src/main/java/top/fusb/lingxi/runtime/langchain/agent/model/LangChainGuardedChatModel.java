package top.fusb.lingxi.runtime.langchain.agent.model;

import top.fusb.lingxi.runtime.langchain.agent.LangChainExecutionContext;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;
import java.util.Set;

public final class LangChainGuardedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final LangChainExecutionContext context;

    public LangChainGuardedChatModel(ChatModel delegate, LangChainExecutionContext context) {
        this.delegate = delegate;
        this.context = context;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        context.beginModelRequest();
        ChatResponse response = delegate.doChat(request);
        context.addUsage(response.tokenUsage());
        return response;
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
