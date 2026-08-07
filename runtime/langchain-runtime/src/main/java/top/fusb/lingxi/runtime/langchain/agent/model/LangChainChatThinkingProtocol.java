package top.fusb.lingxi.runtime.langchain.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import top.fusb.lingxi.runtime.api.model.RuntimeModelConfig;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventContext;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.time.Duration;

/**
 * 封装供应商声明的 Chat Completions 交错思考协议。
 */
public final class LangChainChatThinkingProtocol {

    private LangChainChatThinkingProtocol() {
    }

    /**
     * 判断当前模型供应商是否声明了 Chat Completions 思考字段。
     *
     * @param config Runtime 模型配置
     * @return 配置了思考字段时返回 true
     */
    public static boolean supports(RuntimeModelConfig config) {
        return config != null && config.thinkingFieldName() != null
                && !config.thinkingFieldName().isBlank();
    }

    /**
     * 开启思考内容接收和后续请求回送。
     *
     * @param builder LangChain4j Chat Completions 模型构建器
     * @return 原构建器，便于继续配置
     */
    public static OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder configure(
            OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder,
            RuntimeModelConfig config) {
        return builder.returnThinking(true).sendThinking(true, config.thinkingFieldName().trim());
    }

    /**
     * 包装 HTTP 客户端，在发送请求前补齐供应商要求的空思考字段。
     *
     * @param delegate 实际 HTTP 客户端构建器
     * @param objectMapper JSON 解析器
     * @return 带思考字段规范化能力的构建器
     */
    public static HttpClientBuilder httpClientBuilder(HttpClientBuilder delegate, ObjectMapper objectMapper,
                                                      RuntimeModelConfig config) {
        return new ThinkingHttpClientBuilder(delegate, objectMapper, config.thinkingFieldName().trim());
    }

    static HttpRequest normalizeRequest(HttpRequest request, ObjectMapper objectMapper, String thinkingFieldName) {
        if (request == null || request.body() == null || request.body().isBlank()) {
            return request;
        }
        try {
            JsonNode root = objectMapper.readTree(request.body());
            JsonNode messagesNode = root.path("messages");
            if (!(root instanceof ObjectNode) || !(messagesNode instanceof ArrayNode messages)) {
                return request;
            }
            boolean changed = false;
            for (JsonNode messageNode : messages) {
                if (messageNode instanceof ObjectNode message
                        && "assistant".equals(message.path("role").asText())
                        && !message.has(thinkingFieldName)) {
                    message.put(thinkingFieldName, "");
                    changed = true;
                }
            }
            if (!changed) {
                return request;
            }
            return HttpRequest.builder()
                    .method(request.method())
                    .url(request.url())
                    .headers(request.headers())
                    .formDataFields(request.formDataFields())
                    .formDataFiles(request.formDataFiles())
                    .body(objectMapper.writeValueAsString(root))
                    .build();
        } catch (Exception ignored) {
            // 非标准请求体交给底层客户端原样处理，避免协议适配器掩盖真实模型错误。
            return request;
        }
    }

    static String normalizeResponseData(String data, ObjectMapper objectMapper) {
        if (data == null || data.isBlank() || "[DONE]".equals(data)) {
            return data;
        }
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode usageNode = root.path("usage");
            if (!(usageNode instanceof ObjectNode usage) || !usage.has("prompt_cache_hit_tokens")) {
                return data;
            }
            ObjectNode details = usage.has("prompt_tokens_details") && usage.get("prompt_tokens_details") instanceof ObjectNode existing
                    ? existing : usage.putObject("prompt_tokens_details");
            if (!details.has("cached_tokens")) {
                details.set("cached_tokens", usage.get("prompt_cache_hit_tokens"));
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception ignored) {
            return data;
        }
    }

    private static final class ThinkingHttpClientBuilder implements HttpClientBuilder {

        private final HttpClientBuilder delegate;
        private final ObjectMapper objectMapper;
        private final String thinkingFieldName;

        private ThinkingHttpClientBuilder(HttpClientBuilder delegate, ObjectMapper objectMapper,
                                          String thinkingFieldName) {
            this.delegate = delegate;
            this.objectMapper = objectMapper;
            this.thinkingFieldName = thinkingFieldName;
        }

        @Override
        public Duration connectTimeout() {
            return delegate.connectTimeout();
        }

        @Override
        public HttpClientBuilder connectTimeout(Duration timeout) {
            delegate.connectTimeout(timeout);
            return this;
        }

        @Override
        public Duration readTimeout() {
            return delegate.readTimeout();
        }

        @Override
        public HttpClientBuilder readTimeout(Duration timeout) {
            delegate.readTimeout(timeout);
            return this;
        }

        @Override
        public HttpClient build() {
            return new ThinkingHttpClient(delegate.build(), objectMapper, thinkingFieldName);
        }
    }

    private static final class ThinkingHttpClient implements HttpClient {

        private final HttpClient delegate;
        private final ObjectMapper objectMapper;
        private final String thinkingFieldName;

        private ThinkingHttpClient(HttpClient delegate, ObjectMapper objectMapper, String thinkingFieldName) {
            this.delegate = delegate;
            this.objectMapper = objectMapper;
            this.thinkingFieldName = thinkingFieldName;
        }

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            SuccessfulHttpResponse response = delegate.execute(normalizeRequest(request, objectMapper, thinkingFieldName));
            String normalizedBody = normalizeResponseData(response.body(), objectMapper);
            if (java.util.Objects.equals(normalizedBody, response.body())) {
                return response;
            }
            return SuccessfulHttpResponse.builder()
                    .statusCode(response.statusCode())
                    .headers(response.headers())
                    .body(normalizedBody)
                    .build();
        }

        @Override
        public void execute(HttpRequest request,
                            ServerSentEventParser parser,
                            ServerSentEventListener listener) {
            delegate.execute(normalizeRequest(request, objectMapper, thinkingFieldName), parser, new ServerSentEventListener() {
                @Override
                public void onOpen(SuccessfulHttpResponse response) {
                    listener.onOpen(response);
                }

                @Override
                public void onEvent(ServerSentEvent event, ServerSentEventContext context) {
                    listener.onEvent(normalizeEvent(event), context);
                }

                @Override
                public void onEvent(ServerSentEvent event) {
                    listener.onEvent(normalizeEvent(event));
                }

                @Override
                public void onError(Throwable error) {
                    listener.onError(error);
                }

                @Override
                public void onClose() {
                    listener.onClose();
                }

                private ServerSentEvent normalizeEvent(ServerSentEvent event) {
                    String normalizedData = normalizeResponseData(event.data(), objectMapper);
                    return java.util.Objects.equals(normalizedData, event.data())
                            ? event : new ServerSentEvent(event.event(), normalizedData);
                }
            });
        }
    }
}
