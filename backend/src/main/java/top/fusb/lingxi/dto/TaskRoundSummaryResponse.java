package top.fusb.lingxi.dto;

import top.fusb.lingxi.enums.TaskStatus;
import top.fusb.lingxi.runtime.api.model.RuntimeModelProtocol;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TaskRoundSummaryResponse {

    private Long id;
    private String runtimeCode;
    private String modelProfileId;
    private String modelProviderId;
    private String modelProviderName;
    private String modelName;
    private String modelIdentifier;
    private RuntimeModelProtocol modelProtocol;
    private String modelReasoningEffort;
    private Integer roundNo;
    private String userInput;
    private TaskStatus status;
    private Long requestCount;
    private Long inputTokens;
    private Long cachedInputTokens;
    private Long cacheCreationInputTokens;
    private Long outputTokens;
    private Long reasoningOutputTokens;
    private Long totalTokens;
    private ResourceMemoryMetricsResponse resourceMemoryMetrics;
    private TaskExecutionMetricsResponse executionMetrics;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
