package top.fusb.lingxi.runtime.api.support;

import top.fusb.lingxi.runtime.api.model.RuntimeCostEstimate;
import top.fusb.lingxi.runtime.api.model.RuntimeCostRequest;
import top.fusb.lingxi.runtime.api.model.RuntimeModelPricing;
import top.fusb.lingxi.runtime.api.model.RuntimePricingScheduleRule;
import top.fusb.lingxi.runtime.api.model.RuntimePricingTimeRange;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

public final class RuntimeCostKit {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);

    private RuntimeCostKit() {
    }

    /**
     * 根据执行时价格快照计算一次模型调用费用，算法与 Runtime 和模型供应商解耦。
     *
     * @param request 单次模型调用的时间、Token 用量和价格快照
     * @return 标准费率完整时返回缓存命中、未命中输入、输出和总费用，否则返回空
     */
    public static Optional<RuntimeCostEstimate> estimate(RuntimeCostRequest request) {
        RuntimeModelPricing pricing = request == null ? null : request.pricing();
        if (pricing == null || request.occurredAt() == null || !standardRatesPresent(pricing)) {
            return Optional.empty();
        }
        RuntimePricingScheduleRule rule = matchingRule(request.occurredAt(), pricing);
        BigDecimal multiplier = rule == null ? BigDecimal.ONE : rule.multiplier();
        BigDecimal cacheHitRate = pricing.cacheHitInputPerMillion().multiply(multiplier);
        BigDecimal cacheMissRate = pricing.cacheMissInputPerMillion().multiply(multiplier);
        BigDecimal outputRate = pricing.outputPerMillion().multiply(multiplier);
        long inputTokens = Math.max(0L, value(request.inputTokens()));
        long cachedInputTokens = Math.min(inputTokens, Math.max(0L, value(request.cachedInputTokens())));
        long cacheMissInputTokens = inputTokens - cachedInputTokens;
        long outputTokens = Math.max(0L, value(request.outputTokens()));
        BigDecimal cacheHitAmount = tokenAmount(cachedInputTokens, cacheHitRate);
        BigDecimal cacheMissAmount = tokenAmount(cacheMissInputTokens, cacheMissRate);
        BigDecimal outputAmount = tokenAmount(outputTokens, outputRate);
        BigDecimal total = cacheHitAmount.add(cacheMissAmount).add(outputAmount)
                .setScale(8, RoundingMode.HALF_UP);
        return Optional.of(new RuntimeCostEstimate(
                pricing.currency() == null || pricing.currency().isBlank() ? "CNY" : pricing.currency(),
                rule == null ? "STANDARD" : rule.name(),
                total, cacheHitAmount, cacheMissAmount, outputAmount));
    }

    private static boolean standardRatesPresent(RuntimeModelPricing pricing) {
        return pricing.cacheHitInputPerMillion() != null
                && pricing.cacheMissInputPerMillion() != null
                && pricing.outputPerMillion() != null;
    }

    private static RuntimePricingScheduleRule matchingRule(Instant occurredAt, RuntimeModelPricing pricing) {
        try {
            ZoneId zoneId = ZoneId.of(pricing.timeZone() == null || pricing.timeZone().isBlank()
                    ? "Asia/Shanghai" : pricing.timeZone());
            LocalTime time = occurredAt.atZone(zoneId).toLocalTime();
            for (RuntimePricingScheduleRule rule : safeRules(pricing.scheduleRules())) {
                if (rule == null || rule.multiplier() == null || rule.multiplier().signum() <= 0) {
                    continue;
                }
                for (RuntimePricingTimeRange range : safeRanges(rule.timeRanges())) {
                    if (range != null && includes(time, LocalTime.parse(range.start()), LocalTime.parse(range.end()))) {
                        return rule;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static boolean includes(LocalTime time, LocalTime start, LocalTime end) {
        return start.isBefore(end)
                ? !time.isBefore(start) && time.isBefore(end)
                : !time.isBefore(start) || time.isBefore(end);
    }

    private static List<RuntimePricingScheduleRule> safeRules(List<RuntimePricingScheduleRule> rules) {
        return rules == null ? List.of() : rules;
    }

    private static List<RuntimePricingTimeRange> safeRanges(List<RuntimePricingTimeRange> ranges) {
        return ranges == null ? List.of() : ranges;
    }

    private static BigDecimal tokenAmount(long tokens, BigDecimal pricePerMillion) {
        return BigDecimal.valueOf(tokens).multiply(pricePerMillion)
                .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }
}
