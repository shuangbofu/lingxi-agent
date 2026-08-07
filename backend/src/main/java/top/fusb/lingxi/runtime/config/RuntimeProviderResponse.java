package top.fusb.lingxi.runtime.config;

import lombok.Data;

import java.util.List;

@Data
public class RuntimeProviderResponse {

    private String id;
    private String providerType;
    private String name;
    private String baseUrl;
    private boolean apiKeyConfigured;
    private String apiKeyMasked;
    private String instructionPrompt;
    private List<RuntimeReasoningEffortOption> reasoningEffortOptions;
    private Integer maxConcurrency;
    private boolean enabled;
    private Integer sortOrder;
}
