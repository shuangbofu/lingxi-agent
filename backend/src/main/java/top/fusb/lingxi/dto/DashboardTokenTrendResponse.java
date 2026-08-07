package top.fusb.lingxi.dto;

import lombok.Data;

@Data
public class DashboardTokenTrendResponse {

    private String date;

    private Long taskCount = 0L;

    private Long requestCount = 0L;

    private Long inputTokens = 0L;

    private Long cachedInputTokens = 0L;

    private Long cacheCreationInputTokens = 0L;

    private Long outputTokens = 0L;

    private Long reasoningOutputTokens = 0L;

    private Long totalTokens = 0L;

    private Long averageTokens = 0L;
}
