package top.fusb.lingxi.dto;

import lombok.Data;

@Data
public class TaskExecutionMetricsResponse {

    private Long totalDurationMs;
    private Long firstFeedbackMs;
    private Long commandDurationMs = 0L;
    private Long resultProcessingMs;
    private Long compactionCount = 0L;
    private Long duplicateCapabilityCallCount = 0L;
}
