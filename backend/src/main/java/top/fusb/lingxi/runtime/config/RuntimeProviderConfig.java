package top.fusb.lingxi.runtime.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RuntimeProviderConfig {

    private String id;
    private String providerType;
    private String name;
    private String baseUrl;
    private String apiKey;
    private String instructionPrompt;
    private List<RuntimeReasoningEffortOption> reasoningEffortOptions = new ArrayList<>();
    private Integer maxConcurrency;
    private boolean enabled = true;
    private Integer sortOrder;
}
