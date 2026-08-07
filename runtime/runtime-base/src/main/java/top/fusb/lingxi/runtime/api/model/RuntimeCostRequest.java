package top.fusb.lingxi.runtime.api.model;

import java.time.Instant;

public record RuntimeCostRequest(
        String model,
        Instant occurredAt,
        Long inputTokens,
        Long cachedInputTokens,
        Long outputTokens,
        RuntimeModelPricing pricing
) {
}
