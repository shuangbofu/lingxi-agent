package top.fusb.lingxi.runtime.langchain.core;

import top.fusb.lingxi.runtime.api.AgentRuntime;
import top.fusb.lingxi.runtime.api.model.RuntimeDescriptor;
import top.fusb.lingxi.runtime.api.model.RuntimeModelProtocol;
import top.fusb.lingxi.runtime.api.event.RuntimeEventListener;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionRequest;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionResult;
import top.fusb.lingxi.runtime.api.model.RuntimeSessionRef;
import top.fusb.lingxi.runtime.api.model.RuntimeUsage;
import top.fusb.lingxi.runtime.api.model.RuntimeUsageQuery;
import top.fusb.lingxi.runtime.langchain.util.LangChainErrorMessageKit;

import java.util.Optional;
import java.util.Set;

public class LangChainRuntime implements AgentRuntime {

    public static final String CODE = "langchain";

    private final LangChainRuntimeDelegate delegate;

    public LangChainRuntime(LangChainRuntimeDelegate delegate) {
        this.delegate = delegate;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public RuntimeDescriptor descriptor() {
        return new RuntimeDescriptor(CODE, "灵析自研", "满足基础场景与能力调用，支持更多模型灵活选择。",
                "/app-logo.png", true, false,
                Set.of(RuntimeModelProtocol.RESPONSES, RuntimeModelProtocol.CHAT_COMPLETIONS), true,
                null,
                "首个响应包含网络传输、上游排队和模型首 Token 推理；上游未提供 Server-Timing 时，平台不会把它标记为纯网络耗时。",
                0);
    }

    @Override
    public RuntimeExecutionResult execute(RuntimeExecutionRequest request, RuntimeEventListener listener) {
        try {
            return delegate.execute(request, listener);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(LangChainErrorMessageKit.userMessage(exception), exception);
        }
    }

    @Override
    public boolean cancel(String executionId) {
        return delegate.cancel(executionId);
    }

    @Override
    public long defaultTimeoutSeconds() {
        return delegate.defaultTimeoutSeconds();
    }

    @Override
    public Optional<RuntimeSessionRef> latestSession(String conversationId) {
        return delegate.latestSession(conversationId);
    }

    @Override
    public Optional<RuntimeUsage> readUsage(RuntimeUsageQuery query) {
        return delegate.readUsage(query);
    }

}
