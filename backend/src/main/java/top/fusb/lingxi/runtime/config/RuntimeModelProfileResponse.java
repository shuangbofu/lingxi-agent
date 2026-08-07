package top.fusb.lingxi.runtime.config;

import top.fusb.lingxi.runtime.api.model.RuntimeModelProtocol;
import lombok.Data;

@Data
public class RuntimeModelProfileResponse {

    private String id;
    private String name;
    private String description;
    private String providerId;
    private String model;
    private RuntimeModelProtocol protocol;
    private Integer contextWindowTokens;
    private String reasoningEffort;
    private String instructionPrompt;
    private Integer maxConcurrency;
    private boolean imageInputSupported;
    private boolean enabled;
    private Integer sortOrder;
}
