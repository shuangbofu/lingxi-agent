package top.fusb.lingxi.runtime.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeConcurrencyPolicyTest {

    @Test
    void shouldApplyModelAndProviderLimitsIndependently() {
        RuntimeConcurrencyPolicy policy = new RuntimeConcurrencyPolicy(
                4,
                Map.of("provider-a", 2),
                Map.of("model-a", 1),
                Map.of(
                        "model-a", "provider-a",
                        "model-b", "provider-a",
                        "model-c", "provider-a",
                        "model-d", "provider-b"));
        Map<String, Integer> providerCounts = new HashMap<>();
        Map<String, Integer> modelCounts = new HashMap<>();

        assertThat(policy.allows("model-a", providerCounts, modelCounts)).isTrue();
        policy.acquire("model-a", providerCounts, modelCounts);
        assertThat(policy.allows("model-a", providerCounts, modelCounts)).isFalse();
        assertThat(policy.allows("model-b", providerCounts, modelCounts)).isTrue();
        policy.acquire("model-b", providerCounts, modelCounts);
        assertThat(policy.allows("model-c", providerCounts, modelCounts)).isFalse();
        assertThat(policy.allows("model-d", providerCounts, modelCounts)).isTrue();
    }
}
