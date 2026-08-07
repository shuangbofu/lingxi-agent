package top.fusb.lingxi.runtime.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.runtime.api.model.RuntimeModelProtocol;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeProviderCatalogTest {

    @Test
    void deepSeekSupportsResponsesAndChatCompletions() {
        RuntimeProviderType deepSeek = new RuntimeProviderCatalog(new ObjectMapper())
                .find("DEEPSEEK")
                .orElseThrow();

        assertThat(deepSeek.defaultBaseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(deepSeek.defaultProtocol()).isEqualTo(RuntimeModelProtocol.RESPONSES);
        assertThat(deepSeek.supportedProtocols()).containsExactly(
                RuntimeModelProtocol.RESPONSES,
                RuntimeModelProtocol.CHAT_COMPLETIONS);
    }
}
