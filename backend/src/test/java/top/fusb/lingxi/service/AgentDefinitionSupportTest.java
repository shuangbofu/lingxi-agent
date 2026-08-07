package top.fusb.lingxi.service;

import top.fusb.lingxi.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentDefinitionSupportTest {

    private final AgentDefinitionSupport support = new AgentDefinitionSupport();

    @Test
    void preservesValidExternalCapabilityCodesWithoutRequiringInstallation() {
        Set<String> capabilities = new LinkedHashSet<>();
        capabilities.add("code-repository");
        capabilities.add("local-log-read");

        assertThat(support.parseCapabilities(capabilities))
                .containsExactly("code-repository", "local-log-read");
    }

    @Test
    void rejectsInvalidCapabilityCodes() {
        assertThatThrownBy(() -> support.parseCapabilities(Set.of("Local Log Read")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("能力编码必须是最多 64 位的小写连字符格式");
    }
}
