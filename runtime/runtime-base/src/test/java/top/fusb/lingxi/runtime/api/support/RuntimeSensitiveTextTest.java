package top.fusb.lingxi.runtime.api.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeSensitiveTextTest {

    @Test
    void shouldPreserveSignedUrlsOnlyInsideLinksView() {
        String signedUrl = "http://localhost:5179/#/source-documents/60?projectCode=PRJ1&token=signed-secret&evidence=selected";
        String source = """
                正文 token=plain-secret

                ```lingxi-view
                {"type":"links","items":[{"title":"原文","url":"%s"}]}
                ```

                {"token":"json-secret"}
                """.formatted(signedUrl);

        String result = RuntimeSensitiveText.redact(source, List.of("signed-secret"));

        assertThat(result)
                .contains("\"url\":\"" + signedUrl + "\"")
                .contains("正文 token=" + RuntimeSensitiveText.REDACTED)
                .contains("{\"token\":\"" + RuntimeSensitiveText.REDACTED + "\"}")
                .doesNotContain("plain-secret", "json-secret");
    }

    @Test
    void shouldRedactTokenInOrdinaryJsonWithoutBreakingItsStructure() {
        String source = "{\"url\":\"https://example.com/page?token=secret-value\",\"description\":\"资料\"}";

        String result = RuntimeSensitiveText.redact(source, List.of());

        assertThat(result).isEqualTo(
                "{\"url\":\"https://example.com/page?token=" + RuntimeSensitiveText.REDACTED
                        + "\",\"description\":\"资料\"}");
    }
}
