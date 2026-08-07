package top.fusb.lingxi.dto;

import lombok.Data;

@Data
public class DashboardExecutionMetricsResponse {

    private Long measuredTaskCount = 0L;
    private Long averageTotalDurationMs = 0L;
    private Long averageFirstFeedbackMs = 0L;
    private Long averageCommandDurationMs = 0L;
    private Long averageResultProcessingMs = 0L;
    private Long compactionCount = 0L;
    private Long duplicateCapabilityCallCount = 0L;
}
