package top.fusb.lingxi.runtime.langchain.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.runtime.langchain.agent.LangChainExecutionContext;
import top.fusb.lingxi.runtime.langchain.agent.evidence.LangChainEvidenceStore;
import top.fusb.lingxi.runtime.langchain.agent.memory.LangChainActiveToolResultProjector;

import top.fusb.lingxi.runtime.api.event.RuntimeEvent;
import top.fusb.lingxi.runtime.api.event.RuntimeEventListener;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionEnvironment;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.openai.OpenAiResponsesChatRequestParameters;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LangChainGuardedStreamingChatModelTest {

    @TempDir
    Path tempDir;

    @Test
    void cancelsTheActiveModelStreamThroughItsRealStreamingHandle() {
        TestStreamingHandle handle = new TestStreamingHandle();
        StreamingChatModel delegate = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onPartialResponse(new PartialResponse("started"), new PartialResponseContext(handle));
            }
        };
        LangChainExecutionContext context = context();
        LangChainGuardedStreamingChatModel model = new LangChainGuardedStreamingChatModel(delegate, context);

        model.chat(ChatRequest.builder().messages(UserMessage.from("test")).build(), handler());
        boolean cancelled = context.cancel();

        assertThat(cancelled).isTrue();
        assertThat(handle.isCancelled()).isTrue();
        assertThat(context.usage().requestCount()).isEqualTo(1L);
    }

    @Test
    void preservesToolRequestsAcrossRepeatedModelRounds() {
        List<ChatRequest> requests = new ArrayList<>();
        StreamingChatModel delegate = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                requests.add(request);
            }
        };
        LangChainGuardedStreamingChatModel model = new LangChainGuardedStreamingChatModel(delegate, context());
        ToolSpecification tool = ToolSpecification.builder().name("read_file").build();
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("test"))
                .toolSpecifications(tool)
                .toolChoice(ToolChoice.AUTO)
                .build();

        model.doChat(request, handler());
        model.doChat(request, handler());
        model.doChat(request, handler());

        assertThat(requests).containsExactly(request, request, request);
        assertThat(requests).allSatisfy(forwarded -> {
            assertThat(forwarded.toolSpecifications()).containsExactly(tool);
            assertThat(forwarded.toolChoice()).isEqualTo(ToolChoice.AUTO);
        });
    }

    @Test
    void normalizesEveryModelRequestBeforeDelegating() {
        List<ChatRequest> requests = new ArrayList<>();
        StreamingChatModel delegate = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                requests.add(request);
            }
        };
        LangChainGuardedStreamingChatModel model = new LangChainGuardedStreamingChatModel(delegate, context());
        ToolExecutionRequest missing = ToolExecutionRequest.builder()
                .id("call-missing")
                .name("read_file")
                .arguments("{}")
                .build();

        model.doChat(ChatRequest.builder().messages(AiMessage.from(missing)).build(), handler());
        model.doChat(ChatRequest.builder().messages(
                ToolExecutionResultMessage.from("call-orphan", "grep", "orphan"),
                UserMessage.from("next")).build(), handler());

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).messages())
                .filteredOn(ToolExecutionResultMessage.class::isInstance)
                .singleElement()
                .satisfies(message -> assertThat(((ToolExecutionResultMessage) message).id())
                        .isEqualTo("call-missing"));
        assertThat(requests.get(1).messages()).containsExactly(UserMessage.from("next"));
    }

    @Test
    void disablesToolsAndRequestsFinalAnswerInsideReservedTokenBudget() {
        List<ChatRequest> requests = new ArrayList<>();
        StreamingChatModel delegate = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                requests.add(request);
            }
        };
        LangChainExecutionContext context = new LangChainExecutionContext(
                "finalize", Path.of("."), new RuntimeEventListener() {
                    @Override
                    public void onEvent(RuntimeEvent event) {
                    }
                }, RuntimeExecutionEnvironment.empty(),
                null, 4, 2, 1_000L, 200L);
        context.beginModelRequest();
        context.addUsage(new TokenUsage(700, 100, 800));
        ToolSpecification tool = ToolSpecification.builder().name("read_file").build();
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("test"))
                .toolSpecifications(tool)
                .toolChoice(ToolChoice.AUTO)
                .build();

        new LangChainGuardedStreamingChatModel(delegate, context).doChat(request, handler());

        assertThat(requests).hasSize(1);
        ChatRequest guarded = requests.get(0);
        assertThat(guarded.toolSpecifications()).isEmpty();
        assertThat(guarded.toolChoice()).isEqualTo(ToolChoice.NONE);
        assertThat(guarded.messages().get(guarded.messages().size() - 1).toString())
                .contains("禁止继续调用任何工具", "最终结论");
    }

    @Test
    void preservesCompletedResponseThatCrossesTheHardTokenLimit() {
        StreamingChatModel delegate = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from("最终结论"))
                        .tokenUsage(new TokenUsage(80, 30, 110))
                        .build());
            }
        };
        LangChainExecutionContext context = new LangChainExecutionContext(
                "hard-limit", Path.of("."), new RuntimeEventListener() {
                    @Override
                    public void onEvent(RuntimeEvent event) {
                    }
                }, RuntimeExecutionEnvironment.empty(), null, 4, 2, 100L, 20L);
        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();

        new LangChainGuardedStreamingChatModel(delegate, context).doChat(
                ChatRequest.builder().messages(UserMessage.from("test")).build(),
                new StreamingChatResponseHandler() {
                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        completed.set(response);
                    }

                    @Override
                    public void onError(Throwable error) {
                        failed.set(error);
                    }
                });

        assertThat(completed.get()).isNotNull();
        assertThat(completed.get().aiMessage().text()).isEqualTo("最终结论");
        assertThat(failed.get()).isNull();
        assertThat(context.usage().totalTokens()).isEqualTo(110L);
    }

    @Test
    void preservesResponsesProtocolParametersWhileDisablingAllTools() {
        List<ChatRequest> requests = new ArrayList<>();
        StreamingChatModel delegate = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                requests.add(request);
            }
        };
        LangChainExecutionContext context = new LangChainExecutionContext(
                "responses-finalize", Path.of("."), new RuntimeEventListener() {
                    @Override
                    public void onEvent(RuntimeEvent event) {
                    }
                }, RuntimeExecutionEnvironment.empty(), null, 4, 2, 1_000L, 200L);
        context.beginModelRequest();
        context.addUsage(new TokenUsage(700, 100, 800));
        OpenAiResponsesChatRequestParameters parameters = OpenAiResponsesChatRequestParameters.builder()
                .modelName("gpt-5")
                .reasoningEffort("medium")
                .toolSpecifications(ToolSpecification.builder().name("read_file").build())
                .serverTools(List.of(Map.of("type", "web_search")))
                .toolChoice(ToolChoice.AUTO)
                .build();

        new LangChainGuardedStreamingChatModel(delegate, context).doChat(
                ChatRequest.builder().messages(UserMessage.from("test")).parameters(parameters).build(), handler());

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).parameters()).isInstanceOf(OpenAiResponsesChatRequestParameters.class);
        OpenAiResponsesChatRequestParameters guarded =
                (OpenAiResponsesChatRequestParameters) requests.get(0).parameters();
        assertThat(guarded.reasoningEffort()).isEqualTo("medium");
        assertThat(guarded.toolSpecifications()).isEmpty();
        assertThat(guarded.serverTools()).isEmpty();
        assertThat(guarded.toolChoice()).isEqualTo(ToolChoice.NONE);
    }

    @Test
    void emergencyProjectsNewestToolResultAtActualRequestBoundary() throws Exception {
        Files.createDirectories(tempDir.resolve("runtime-state"));
        List<ChatRequest> requests = new ArrayList<>();
        StreamingChatModel delegate = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                requests.add(request);
            }
        };
        CharacterTokenEstimator estimator = new CharacterTokenEstimator();
        LangChainEvidenceStore evidenceStore = new LangChainEvidenceStore(
                tempDir.resolve("runtime-state/evidence"), new ObjectMapper());
        LangChainActiveToolResultProjector projector = new LangChainActiveToolResultProjector(
                "request-capacity", estimator, evidenceStore, 100);
        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("call-large")
                .name("read_file")
                .arguments("{}")
                .build();
        ChatRequest request = ChatRequest.builder()
                .messages(
                        UserMessage.from("检查文件"),
                        AiMessage.from(toolRequest),
                        ToolExecutionResultMessage.from(toolRequest, "大结果" + "甲".repeat(3_000)))
                .toolSpecifications(ToolSpecification.builder().name("read_file").build())
                .build();

        new LangChainGuardedStreamingChatModel(
                delegate, context(), new LangChainContextBudget(estimator, 1_000, 0.9D, null, null),
                projector, new LangChainModelInputNormalizer(true, true)).doChat(request, handler());

        assertThat(requests).hasSize(1);
        assertThat(((ToolExecutionResultMessage) requests.get(0).messages().get(2)).text())
                .contains("langchain.active_tool_result_evidence", "read_evidence");
    }

    private LangChainExecutionContext context() {
        return new LangChainExecutionContext("stream", Path.of("."), new RuntimeEventListener() {
            @Override
            public void onEvent(RuntimeEvent event) {
            }
        }, null, 4, 2, 1_000L);
    }

    private StreamingChatResponseHandler handler() {
        return new StreamingChatResponseHandler() {
            @Override
            public void onCompleteResponse(ChatResponse response) {
            }

            @Override
            public void onError(Throwable error) {
            }
        };
    }

    private static final class TestStreamingHandle implements StreamingHandle {

        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    private static final class CharacterTokenEstimator implements TokenCountEstimator {

        @Override
        public int estimateTokenCountInText(String text) {
            return text == null ? 0 : text.length();
        }

        @Override
        public int estimateTokenCountInMessage(ChatMessage message) {
            return ChatMessageSerializer.messageToJson(message).length();
        }

        @Override
        public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
            int tokens = 0;
            for (ChatMessage message : messages) {
                tokens += estimateTokenCountInMessage(message);
            }
            return tokens;
        }
    }
}
