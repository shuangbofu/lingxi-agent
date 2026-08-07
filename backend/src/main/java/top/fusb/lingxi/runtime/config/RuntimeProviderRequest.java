package top.fusb.lingxi.runtime.config;

import lombok.Data;

@Data
public class RuntimeProviderRequest {

    private String id;
    private String providerType;
    private String name;
    private String baseUrl;
    private String apiKey;
    private String instructionPrompt;
    private Integer maxConcurrency;
    private boolean enabled = true;
    private Integer sortOrder;
}
