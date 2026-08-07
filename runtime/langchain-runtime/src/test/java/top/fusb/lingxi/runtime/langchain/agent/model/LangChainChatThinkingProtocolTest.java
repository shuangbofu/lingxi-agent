package top.fusb.lingxi.runtime.langchain.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.runtime.api.model.RuntimeModelConfig;
import top.fusb.lingxi.runtime.api.model.RuntimeModelProtocol;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LangChainChatThinkingProtocolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void recognizesProviderDeclaredThinkingField() {
        RuntimeModelConfig thinkingModel = new RuntimeModelConfig(
                "key", "SYSTEM", "https://example.test/v1", "any-model-name",
                "high", 128_000, RuntimeModelProtocol.CHAT_COMPLETIONS, null,
                false, "reasoning_content");
        RuntimeModelConfig other = new RuntimeModelConfig(
                "key", "SYSTEM", "https://example.test/v1", "gpt-test",
                null, 128_000, RuntimeModelProtocol.CHAT_COMPLETIONS, true);

        assertThat(LangChainChatThinkingProtocol.supports(thinkingModel)).isTrue();
        assertThat(LangChainChatThinkingProtocol.supports(other)).isFalse();
    }

    @Test
    void addsEmptyReasoningContentToAssistantMessagesOnly() throws Exception {
        HttpRequest request = HttpRequest.builder()
                .method(HttpMethod.POST)
                .url("https://example.test/v1/chat/completions")
                .addHeader("Content-Type", "application/json")
                .body("""
                        {
                          "model":"deepseek-v4",
                          "messages":[
                            {"role":"system","content":"system"},
                            {"role":"assistant","content":"done"},
                            {"role":"assistant","content":null,"reasoning_content":"kept","tool_calls":[]},
                            {"role":"tool","content":"result","tool_call_id":"call-1"}
                          ]
                        }
                        """)
                .build();

        HttpRequest normalized = LangChainChatThinkingProtocol.normalizeRequest(
                request, objectMapper, "reasoning_content");
        JsonNode messages = objectMapper.readTree(normalized.body()).path("messages");

        assertThat(messages.get(0).has("reasoning_content")).isFalse();
        assertThat(messages.get(1).path("reasoning_content").asText()).isEmpty();
        assertThat(messages.get(2).path("reasoning_content").asText()).isEqualTo("kept");
        assertThat(messages.get(3).has("reasoning_content")).isFalse();
    }

    @Test
    void mapsDeepSeekCacheHitUsageToOpenAiTokenDetails() throws Exception {
        String normalized = LangChainChatThinkingProtocol.normalizeResponseData("""
                {"usage":{"prompt_tokens":1200,"completion_tokens":100,"total_tokens":1300,
                "prompt_cache_hit_tokens":800,"prompt_cache_miss_tokens":400}}
                """, objectMapper);

        JsonNode usage = objectMapper.readTree(normalized).path("usage");
        assertThat(usage.path("prompt_tokens_details").path("cached_tokens").asLong()).isEqualTo(800L);
        assertThat(usage.path("prompt_cache_miss_tokens").asLong()).isEqualTo(400L);
    }
}
