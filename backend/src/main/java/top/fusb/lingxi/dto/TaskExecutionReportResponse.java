package top.fusb.lingxi.dto;

import top.fusb.lingxi.enums.TaskEventStatus;
import top.fusb.lingxi.enums.TaskExecutionReportStepType;
import top.fusb.lingxi.enums.TaskStatus;
import top.fusb.lingxi.runtime.api.event.RuntimeModelTimingMode;
import lombok.Data;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class TaskExecutionReportResponse {

    private Long taskId;
    private String title;
    private TaskStatus status;
    private String runtimeCode;
    private String modelProfileId;
    private String modelName;
    private String modelIdentifier;
    private String modelTimingNote;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long totalDurationMs = 0L;
    private Long modelApiWaitDurationMs = 0L;
    private Long modelStreamingDurationMs = 0L;
    private Long modelDecisionDurationMs = 0L;
    private Long modelGenerationDurationMs = 0L;
    private Long modelProcessingDurationMs = 0L;
    private Long orchestrationDurationMs = 0L;
    private Long commandWallDurationMs = 0L;
    private Long commandExecutionDurationMs = 0L;
    private Long resultProcessingMs = 0L;
    private Long firstFeedbackMs;
    private Long requestCount = 0L;
    private Long modelRoundCount = 0L;
    private Long toolCallCount = 0L;
    private Long inputTokens = 0L;
    private Long cachedInputTokens = 0L;
    private Long cacheCreationInputTokens = 0L;
    private Long outputTokens = 0L;
    private Long reasoningOutputTokens = 0L;
    private Long totalTokens = 0L;
    private Long estimatedInputTokens = 0L;
    private Long systemInstructionTokens = 0L;
    private Long taskInstructionTokens = 0L;
    private Long mcpInstructionTokens = 0L;
    private Long toolSchemaTokens = 0L;
    private Long conversationTokens = 0L;
    private Long toolResultTokens = 0L;
    private Long imageTokens = 0L;
    private String costCurrency;
    private BigDecimal costAmount;
    private BigDecimal cacheHitInputCost;
    private BigDecimal cacheMissInputCost;
    private BigDecimal outputCost;
    private String priceTier;
    private Long compactionCount = 0L;
    private Long duplicateCapabilityCallCount = 0L;
    private String primaryFinding;
    private String primaryFindingDetail;
    private List<Step> steps = new ArrayList<>();
    private List<ModelCall> modelCalls = new ArrayList<>();

    @Data
    public static class Step {
        private String id;
        private TaskExecutionReportStepType type;
        private String title;
        private String detail;
        private TaskEventStatus status;
        private LocalDateTime startedAt;
        private LocalDateTime endedAt;
        private Long durationMs = 0L;
    }

    @Data
    public static class ModelCall {
        private String id;
        private Integer sequence;
        private String purpose;
        private String model;
        private RuntimeModelTimingMode modelTimingMode;
        private String responseKind;
        private TaskEventStatus status;
        private LocalDateTime startedAt;
        private LocalDateTime firstResponseAt;
        private LocalDateTime endedAt;
        private Long firstResponseMs = 0L;
        private Long generationMs = 0L;
        private Long totalDurationMs = 0L;
        private Long inputTokens;
        private Long cachedInputTokens;
        private Long outputTokens;
        private Long reasoningOutputTokens;
        private Long totalTokens;
        private Long estimatedInputTokens;
        private Long systemInstructionTokens;
        private Long taskInstructionTokens;
        private Long mcpInstructionTokens;
        private Long toolSchemaTokens;
        private Long conversationTokens;
        private Long toolResultTokens;
        private Long imageTokens;
        private String costCurrency;
        private BigDecimal costAmount;
        private BigDecimal cacheHitInputCost;
        private BigDecimal cacheMissInputCost;
        private BigDecimal outputCost;
        private String priceTier;
        private Integer messageCount;
        private Integer toolDefinitionCount;
        private Integer toolRequestCount;
        private Double outputTokensPerSecond;
        private String diagnosisType;
        private String diagnosis;
        private String diagnosisDetail;
        private String errorType;
        private String errorMessage;
    }
}
