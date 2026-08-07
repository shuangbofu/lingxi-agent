package top.fusb.lingxi.kit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SecretValueKitTest {

    @Test
    void shouldMaskSecretWithoutReturningFullValue() {
        assertEquals("sk-xfewf****sdfsdf", SecretValueKit.mask("sk-xfewf-secret-middle-sdfsdf"));
        assertEquals("abc****xyz", SecretValueKit.mask("abcdefghi-xyz"));
        assertEquals("****", SecretValueKit.mask("short"));
        assertEquals(null, SecretValueKit.mask(null));
    }
}
