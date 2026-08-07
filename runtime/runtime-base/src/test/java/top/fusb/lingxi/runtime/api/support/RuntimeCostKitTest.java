package top.fusb.lingxi.runtime.api.support;

import top.fusb.lingxi.runtime.api.AgentRuntime;
import top.fusb.lingxi.runtime.api.event.RuntimeEventListener;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionRequest;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionResult;
import top.fusb.lingxi.runtime.api.model.RuntimeCostEstimate;
import top.fusb.lingxi.runtime.api.model.RuntimeCostRequest;
import top.fusb.lingxi.runtime.api.model.RuntimeModelPricing;
import top.fusb.lingxi.runtime.api.model.RuntimePricingScheduleRule;
import top.fusb.lingxi.runtime.api.model.RuntimePricingTimeRange;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeCostKitTest {

    private final RuntimeModelPricing pricing = new RuntimeModelPricing(
            "CNY", "Asia/Shanghai",
            new BigDecimal("0.2"), new BigDecimal("2"), new BigDecimal("3"),
            List.of(new RuntimePricingScheduleRule("高峰价", new BigDecimal("2"), List.of(
                    new RuntimePricingTimeRange("09:00", "12:00"),
                    new RuntimePricingTimeRange("14:00", "18:00")))));

    @Test
    void calculatesStandardPriceForEveryRuntimeUsingTheSharedContract() {
        AgentRuntime runtime = runtimeWithoutCustomPricing();
        RuntimeCostEstimate cost = runtime.estimateCost(new RuntimeCostRequest(
                "any-model", Instant.parse("2026-07-26T04:00:00Z"),
                1_000_000L, 250_000L, 100_000L, pricing)).orElseThrow();

        assertThat(cost.priceTier()).isEqualTo("STANDARD");
        assertThat(cost.cacheHitInputAmount()).isEqualByComparingTo("0.05000000");
        assertThat(cost.cacheMissInputAmount()).isEqualByComparingTo("1.50000000");
        assertThat(cost.outputAmount()).isEqualByComparingTo("0.30000000");
        assertThat(cost.amount()).isEqualByComparingTo("1.85000000");
    }

    @Test
    void appliesScheduledMultiplierAcrossMultipleTimeRangesUsingConfiguredTimeZone() {
        RuntimeCostEstimate morningPeak = RuntimeCostKit.estimate(new RuntimeCostRequest(
                "any-model", Instant.parse("2026-07-26T02:00:00Z"),
                1_000_000L, 250_000L, 100_000L, pricing)).orElseThrow();
        RuntimeCostEstimate afternoonPeak = RuntimeCostKit.estimate(new RuntimeCostRequest(
                "any-model", Instant.parse("2026-07-26T07:00:00Z"),
                1_000_000L, 250_000L, 100_000L, pricing)).orElseThrow();

        assertThat(morningPeak.priceTier()).isEqualTo("高峰价");
        assertThat(morningPeak.amount()).isEqualByComparingTo("3.70000000");
        assertThat(afternoonPeak.priceTier()).isEqualTo("高峰价");
        assertThat(afternoonPeak.amount()).isEqualByComparingTo("3.70000000");
    }

    private AgentRuntime runtimeWithoutCustomPricing() {
        return new AgentRuntime() {
            @Override
            public String code() {
                return "test";
            }

            @Override
            public RuntimeExecutionResult execute(RuntimeExecutionRequest request, RuntimeEventListener listener) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean cancel(String executionId) {
                return false;
            }
        };
    }
}
