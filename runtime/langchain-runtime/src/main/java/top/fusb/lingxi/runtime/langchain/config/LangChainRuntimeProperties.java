package top.fusb.lingxi.runtime.langchain.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "lingxi.langchain")
public class LangChainRuntimeProperties {

    private long defaultTimeoutSeconds = 900L;
    private long timeFinalizationGraceSeconds = 120L;
    private int toolConcurrency = 8;
    private int maxToolCallsPerExecution = 96;
    private int maxEvidenceReadsPerExecution = 48;
    private int maxRepeatedCapabilityCalls = 3;
    private long maxModelTokensPerExecution;
    private long modelTokenFinalizationReserve = 300_000L;
    private int maxMemoryTokens = 128_000;
    private int reservedOutputTokens = 20_000;
    private double compactionTriggerRatio = 0.9D;
    private int compactionRecentTokens = 8_000;
    private int compactionRecentMinimumTokens = 2_000;
    private int compactionRecentTurns = 2;
    private int compactionMinimumTokens = 16_000;
    private int compactionMaxOutputTokens = 4_000;
    private int retainedToolOutputTokens = 40_000;
    private int toolOutputPruneMinimumTokens = 20_000;
    private int activeToolResultMaxTokens = 2_048;
    private int imageTokenEstimate = 3_072;
    private int toolOutputMaxChars = 12_000;
    private int toolOutputPreviewChars = 4_000;
    private int workspaceFileMaxChars = 200_000;
    private long jsonQueryMaxBytes = 64L * 1024L * 1024L;
    private long workspaceImageMaxBytes = 20L * 1024L * 1024L;
    private Duration capabilityCommandTimeout = Duration.ofMinutes(5);
    private int modelMaxAttempts = 3;
    private Duration modelRetryInitialBackoff = Duration.ofMillis(500);
    private Duration modelRetryMaximumBackoff = Duration.ofSeconds(5);
    private String sharedRepositoryCheckoutRoot = "./data/git/checkouts";
    private final Debug debug = new Debug();
    private final Mcp mcp = new Mcp();

    @Data
    public static class Debug {
        private boolean requestInputSnapshotsEnabled;
    }

    @Data
    public static class Mcp {
        private Duration requestTimeout = Duration.ofMinutes(30);
        private boolean logEvents;
        private int duplicateToolCallLimit = 3;
    }
}
