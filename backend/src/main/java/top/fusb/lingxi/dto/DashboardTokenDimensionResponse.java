package top.fusb.lingxi.dto;

import lombok.Data;

@Data
public class DashboardTokenDimensionResponse {

    private String dimensionKey;

    private String dimensionName;

    private Long ownerUserId;

    private String ownerUsername;

    private String ownerDisplayName;

    private String scenario;

    private String scenarioCode;

    private String scenarioName;

    private String scenarioColor;

    private String modelName;

    private String modelIdentifier;

    private Long taskCount = 0L;

    private Long requestCount = 0L;

    private Long pendingCount = 0L;

    private Long runningCount = 0L;

    private Long waitingUserCount = 0L;

    private Long successCount = 0L;

    private Long failedCount = 0L;

    private Long canceledCount = 0L;

    private Long inputTokens = 0L;

    private Long cachedInputTokens = 0L;

    private Long cacheCreationInputTokens = 0L;

    private Long outputTokens = 0L;

    private Long reasoningOutputTokens = 0L;

    private Long totalTokens = 0L;

    private Long averageTokens = 0L;
}
