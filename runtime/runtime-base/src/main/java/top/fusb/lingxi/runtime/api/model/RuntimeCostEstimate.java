package top.fusb.lingxi.runtime.api.model;

import java.math.BigDecimal;

public record RuntimeCostEstimate(
        String currency,
        String priceTier,
        BigDecimal amount,
        BigDecimal cacheHitInputAmount,
        BigDecimal cacheMissInputAmount,
        BigDecimal outputAmount
) {
}
