package top.fusb.lingxi.runtime.api.model;

import java.math.BigDecimal;
import java.util.List;

public record RuntimePricingScheduleRule(
        String name,
        BigDecimal multiplier,
        List<RuntimePricingTimeRange> timeRanges
) {
}
