package top.fusb.lingxi.entity;

import top.fusb.lingxi.dto.TaskResultData;
import top.fusb.lingxi.dto.TaskInputValue;
import top.fusb.lingxi.dto.TaskAttachmentResponse;
import top.fusb.lingxi.enums.TaskStatus;
import top.fusb.lingxi.runtime.api.model.RuntimeModelProtocol;
import top.fusb.lingxi.runtime.api.model.RuntimeModelPricing;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@Entity
@DynamicUpdate
@Table(name = "agent_task")
public class AgentTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 80)
    private String scenarioCode;

    @Column(length = 100)
    private String scenarioName;

    @Column(length = 50)
    private String scenarioIcon;

    @Column(length = 20)
    private String scenarioColor;

    @Column(length = 50)
    private String resultFormat;

    @Column(length = 50)
    private String resultRenderer;

    private Integer queuePriority;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "premise_id")
    private AnalysisPremiseEntity premise;

    @Column(length = 120)
    private String premiseSnapshotName;

    @Column(length = 500)
    private String premiseSnapshotDescription;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> premiseSnapshotContextValues;

    @Lob
    private String premiseSnapshotPromptText;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id")
    private UserEntity owner;

    @Column(nullable = false, length = 50, columnDefinition = "varchar(50)")
    private String scenario;

    @Column(nullable = false, length = 50)
    private String runtimeCode;

    @Column(length = 100)
    private String modelProfileId;

    @Column(length = 100)
    private String modelProviderId;

    @Column(length = 120)
    private String modelProviderName;

    @Column(length = 120)
    private String modelName;

    @Column(length = 160)
    private String modelIdentifier;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 30)
    private RuntimeModelProtocol modelProtocol;

    private Integer modelContextWindowTokens;

    @Column(length = 50)
    private String modelReasoningEffort;

    @Lob
    private String modelInstructionPrompt;

    @JdbcTypeCode(SqlTypes.JSON)
    private RuntimeModelPricing modelPricing;

    private Boolean modelImageInputSupported;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private TaskStatus status;

    @Column(nullable = false, length = 500)
    private String title;

    @Lob
    @Column(nullable = false)
    private String userInput;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<TaskInputValue> inputValues;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<TaskAttachmentResponse> attachments;

    @JdbcTypeCode(SqlTypes.JSON)
    private Set<String> enabledCapabilityCodes;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Set<String>> enabledCapabilityCommands;

    @Lob
    private String prompt;

    @Lob
    private String runtimeInstructions;

    @Lob
    private String runtimeUserMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    private Set<String> presentations;

    @Lob
    private String finalResponseInstructions;

    @Column(length = 2000)
    private String stdoutText;

    @Column(length = 2000)
    private String stderrText;

    @Column(length = 2000)
    private String resultText;

    @JdbcTypeCode(SqlTypes.JSON)
    private TaskResultData resultData;

    private Integer exitCode;

    private Long sourceTaskId;

    private Long conversationRootTaskId;

    private Integer roundNo = 1;

    @Column(name = "runtime_session_id", length = 100)
    private String runtimeSessionId;

    @Column(name = "runtime_session_path", length = 1000)
    private String runtimeSessionPath;

    private Long requestCount;

    private Long inputTokens;

    private Long cachedInputTokens;

    private Long cacheCreationInputTokens;

    private Long outputTokens;

    private Long reasoningOutputTokens;

    private Long totalTokens;

    private Long resourceMemorySearchCount;

    private Long resourceMemoryHitCount;

    private Long resourceMemoryCandidateCount;

    private Long resourceMemorySaveCount;

    private Long resourceMemoryCreatedCount;

    private Long resourceMemoryRefreshedCount;

    private Long resourceMemoryExpiredCount;

    private Long resourceMemoryInvalidatedCount;

    private Long executionFirstFeedbackMs;

    private Long executionCommandDurationMs;

    private Long executionCompactionCount;

    private Long executionDuplicateCapabilityCallCount;

    private LocalDateTime startedAt;

    private LocalDateTime engineCompletedAt;

    private LocalDateTime endedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
