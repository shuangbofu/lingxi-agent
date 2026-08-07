package top.fusb.lingxi.runtime.api.model;

public record RuntimeUsage(
        String eventTimestamp,
        Long requestCount,
        Long inputTokens,
        Long cachedInputTokens,
        Long cacheCreationInputTokens,
        Long outputTokens,
        Long reasoningOutputTokens,
        Long totalTokens,
        Long lastInputTokens,
        Long lastCachedInputTokens,
        Long lastCacheCreationInputTokens,
        Long lastOutputTokens,
        Long lastReasoningOutputTokens,
        Long lastTotalTokens,
        Long modelContextWindow
) {
}
