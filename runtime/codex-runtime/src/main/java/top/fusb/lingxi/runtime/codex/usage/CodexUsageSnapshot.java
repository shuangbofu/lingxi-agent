package top.fusb.lingxi.runtime.codex.usage;

import lombok.Data;

@Data
public class CodexUsageSnapshot {

    private String eventTimestamp;
    private Long requestCount;
    private Long inputTokens;
    private Long cachedInputTokens;
    private Long cacheCreationInputTokens;
    private Long outputTokens;
    private Long reasoningOutputTokens;
    private Long totalTokens;
    private Long lastInputTokens;
    private Long lastCachedInputTokens;
    private Long lastCacheCreationInputTokens;
    private Long lastOutputTokens;
    private Long lastReasoningOutputTokens;
    private Long lastTotalTokens;
    private Long modelContextWindow;
}
