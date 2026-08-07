package top.fusb.lingxi.runtime.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RuntimeModelPricing(
        String currency,
        String timeZone,
        BigDecimal cacheHitInputPerMillion,
        BigDecimal cacheMissInputPerMillion,
        BigDecimal outputPerMillion,
        List<RuntimePricingScheduleRule> scheduleRules
) {
}
