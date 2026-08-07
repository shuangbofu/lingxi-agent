package top.fusb.lingxi.kit;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DefinitionAssetUrlKitTest {

    @Test
    void shouldVersionConfiguredIconUrl() {
        LocalDateTime updatedAt = LocalDateTime.of(2026, 7, 23, 16, 30, 0);

        String result = DefinitionAssetUrlKit.iconUrl("scenarios", "intelligent-testing", "icon.svg", updatedAt);

        assertThat(result).isEqualTo("/api/definition-assets/scenarios/intelligent-testing/icon?v=1784824200000");
    }

    @Test
    void shouldOmitUrlWhenIconIsNotConfigured() {
        assertThat(DefinitionAssetUrlKit.iconUrl("scenarios", "sample", " ", LocalDateTime.now())).isNull();
    }
}
