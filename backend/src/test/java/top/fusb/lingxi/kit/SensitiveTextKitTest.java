package top.fusb.lingxi.kit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveTextKitTest {

    @Test
    void shouldRedactKnownAndStructuredSecrets() {
        String known = "known-secret-value";
        String source = "Authorization: Bearer abc.def.ghi password=hunter2 url=https://user:pass@example.com key=" + known;

        String result = SensitiveTextKit.redact(source, List.of(known));

        assertFalse(result.contains(known));
        assertFalse(result.contains("abc.def.ghi"));
        assertFalse(result.contains("hunter2"));
        assertFalse(result.contains(":pass@"));
        assertTrue(result.contains(SensitiveTextKit.REDACTED));
    }

    @Test
    void shouldExtractOnlyValuesUnderSensitiveKeys() {
        Map<String, Object> config = Map.of(
                "baseUrl", "https://example.com",
                "credentials", Map.of("value", "secret-key-value"),
                "items", List.of(Map.of("password", "secret-password"))
        );

        List<String> values = SensitiveTextKit.sensitiveValues(config);

        assertEquals(2, values.size());
        assertTrue(values.contains("secret-key-value"));
        assertTrue(values.contains("secret-password"));
    }
}
