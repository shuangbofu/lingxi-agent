package top.fusb.lingxi.runtime.langchain.agent.model;

import top.fusb.lingxi.runtime.langchain.agent.LangChainExecutionContext;
import top.fusb.lingxi.runtime.langchain.agent.memory.LangChainActiveToolResultProjector;
import top.fusb.lingxi.runtime.langchain.util.LangChainPromptKit;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiResponsesChatRequestParameters;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class LangChainGuardedStreamingChatModel implements StreamingChatModel {

    private final StreamingChatModel delegate;
    private final LangChainExecutionContext context;
    private final LangChainContextBudget contextBudget;
    private final LangChainActiveToolResultProjector activeToolResultProjector;
    private final LangChainModelInputNormalizer inputNormalizer;

    public LangChainGuardedStreamingChatModel(StreamingChatModel delegate, LangChainExecutionContext context) {
        this(delegate, context, null, null, new LangChainModelInputNormalizer(true, true));
    }

    public LangChainGuardedStreamingChatModel(StreamingChatModel delegate,
                                              LangChainExecutionContext context,
                                              LangChainContextBudget contextBudget,
                                              LangChainActiveToolResultProjector activeToolResultProjector,
                                              LangChainModelInputNormalizer inputNormalizer) {
        this.delegate = delegate;
        this.context = context;
        this.contextBudget = contextBudget;
        this.activeToolResultProjector = activeToolResultProjector;
        this.inputNormalizer = inputNormalizer == null
                ? new LangChainModelInputNormalizer(true, true) : inputNormalizer;
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        try {
            context.beginModelRequest();
        } catch (RuntimeException exception) {
            handler.onError(exception);
            return;
        }
        ChatRequest guardedRequest = request;
        if (context.finalizationRequired()) {
            var messages = new ArrayList<>(request.messages());
            messages.add(UserMessage.from(LangChainPromptKit.load("langchain-budget-finalization.md")));
            guardedRequest = ChatRequest.builder()
                    .messages(messages)
                    .parameters(finalizationParameters(request.parameters()))
                    .build();
        }
        try {
            guardedRequest = inputNormalizer.normalize(guardedRequest);
            guardedRequest = enforceRequestCapacity(guardedRequest);
        } catch (RuntimeException exception) {
            handler.onError(exception);
            return;
        }
        AtomicReference<StreamingHandle> activeHandle = new AtomicReference<>();
        delegate.doChat(guardedRequest, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                if (!context.isCancelled()) {
                    handler.onPartialResponse(partialResponse);
                }
            }

            @Override
            public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext partialContext) {
                register(partialContext.streamingHandle());
                if (!context.isCancelled()) {
                    handler.onPartialResponse(partialResponse, partialContext);
                }
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                if (!context.isCancelled()) {
                    handler.onPartialThinking(partialThinking);
                }
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext partialContext) {
                register(partialContext.streamingHandle());
                if (!context.isCancelled()) {
                    handler.onPartialThinking(partialThinking, partialContext);
                }
            }

            @Override
            public void onPartialToolCall(PartialToolCall partialToolCall) {
                if (!context.isCancelled()) {
                    handler.onPartialToolCall(partialToolCall);
                }
            }

            @Override
            public void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext partialContext) {
                register(partialContext.streamingHandle());
                if (!context.isCancelled()) {
                    handler.onPartialToolCall(partialToolCall, partialContext);
                }
            }

            @Override
            public void onCompleteToolCall(CompleteToolCall completeToolCall) {
                if (!context.isCancelled()) {
                    handler.onCompleteToolCall(completeToolCall);
                }
            }

            @Override
            public void onUnmappedRawEvent(Object event) {
                if (!context.isCancelled()) {
                    handler.onUnmappedRawEvent(event);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                clear();
                if (context.isCancelled()) {
                    return;
                }
                try {
                    context.addUsage(response.tokenUsage());
                    handler.onCompleteResponse(response);
                } catch (RuntimeException exception) {
                    handler.onError(exception);
                }
            }

            @Override
            public void onError(Throwable error) {
                clear();
                if (!context.isCancelled()) {
                    handler.onError(error);
                }
            }

            private void register(StreamingHandle handle) {
                activeHandle.set(handle);
                context.registerStreamingHandle(handle);
            }

            private void clear() {
                StreamingHandle handle = activeHandle.getAndSet(null);
                if (handle != null) {
                    context.clearStreamingHandle(handle);
                }
            }
        });
    }

    /**
     * 在实际模型请求边界估算消息和工具定义，必要时允许归档最新完整工具步骤。
     *
     * @param request 已应用收尾模式约束的模型请求
     * @return 可在输入预算内发送的原请求或紧急投影请求
     * @throws IllegalStateException 紧急投影后请求仍超过输入预算时抛出
     */
    private ChatRequest enforceRequestCapacity(ChatRequest request) {
        if (contextBudget == null || activeToolResultProjector == null) {
            return request;
        }
        contextBudget.reserveToolSpecifications(request.toolSpecifications());
        LangChainContextBudget.Snapshot original = contextBudget.estimate(request);
        if (!original.exceedsInputBudget()) {
            return request;
        }
        List<ChatMessage> projected =
                activeToolResultProjector.projectCurrentTurn(request.messages(), true);
        ChatRequest projectedRequest = request.toBuilder().messages(projected).build();
        LangChainContextBudget.Snapshot projectedBudget = contextBudget.estimate(projectedRequest);
        if (projectedBudget.exceedsInputBudget()) {
            throw new IllegalStateException("LangChain 模型请求超过输入预算，且紧急工具结果投影后仍无法恢复；"
                    + "messages=" + projected.size() + "，tokens=" + projectedBudget.estimatedInputTokens()
                    + "，budget=" + projectedBudget.inputTokenBudget());
        }
        return projectedRequest;
    }

    /**
     * 保留模型协议专用参数，同时清除客户端工具和服务端工具定义。
     *
     * @param source 原始模型请求参数
     * @return 禁止工具调用的最终交付请求参数
     */
    private ChatRequestParameters finalizationParameters(ChatRequestParameters source) {
        if (source instanceof OpenAiResponsesChatRequestParameters parameters) {
            return OpenAiResponsesChatRequestParameters.builder()
                    .overrideWith(parameters)
                    .toolSpecifications(List.of())
                    .serverTools(List.of())
                    .toolChoice(ToolChoice.NONE)
                    .build();
        }
        if (source instanceof OpenAiChatRequestParameters parameters) {
            return OpenAiChatRequestParameters.builder()
                    .overrideWith(parameters)
                    .toolSpecifications(List.of())
                    .toolChoice(ToolChoice.NONE)
                    .build();
        }
        return ChatRequestParameters.builder()
                .overrideWith(source)
                .toolSpecifications(List.of())
                .toolChoice(ToolChoice.NONE)
                .build();
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
