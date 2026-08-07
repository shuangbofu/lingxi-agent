package top.fusb.lingxi.dto;

import top.fusb.lingxi.enums.TaskStatus;
import top.fusb.lingxi.runtime.api.model.RuntimeModelProtocol;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
public class TaskResponse {

    private Long id;
    private Long ownerUserId;
    private String ownerUsername;
    private String ownerDisplayName;
    private String scenarioCode;
    private String scenarioName;
    private String scenarioIconUrl;
    private String scenarioColor;
    private String resultRenderer;
    private Set<String> recommendedScenarioCodes;
    private Long premiseId;
    private String premiseName;
    private String premiseSnapshotName;
    private String premiseSnapshotDescription;
    private Map<String, String> premiseSnapshotContextValues;
    private String scenario;
    private String runtimeCode;
    private String modelProfileId;
    private String modelProviderId;
    private String modelProviderName;
    private String modelName;
    private String modelIdentifier;
    private RuntimeModelProtocol modelProtocol;
    private Integer modelContextWindowTokens;
    private String modelReasoningEffort;
    private TaskStatus status;
    private String title;
    private String userInput;
    private List<TaskInputValue> inputValues;
    private List<TaskAttachmentResponse> attachments;
    private String prompt;
    private String stdoutText;
    private String stderrText;
    private String resultText;
    private TaskResultData resultData;
    private Integer exitCode;
    private Long sourceTaskId;
    private Long conversationRootTaskId;
    private Integer roundNo;
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
    private List<TaskEventResponse> events;
    private List<TaskEventEntryResponse> eventEntries;
    private List<TaskInteractionResponse> interactions;
    private Integer roundCount;
    private List<TaskRoundSummaryResponse> roundSummaries;
}
