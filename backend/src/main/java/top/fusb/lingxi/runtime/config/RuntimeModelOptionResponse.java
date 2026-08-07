package top.fusb.lingxi.runtime.config;

import top.fusb.lingxi.runtime.api.model.RuntimeModelProtocol;
import lombok.Data;

@Data
public class RuntimeModelOptionResponse {

    private String id;
    private String name;
    private String description;
    private String providerId;
    private String providerName;
    private String providerType;
    private String providerTypeName;
    private String providerIcon;
    private String providerDarkIcon;
    private String model;
    private RuntimeModelProtocol protocol;
    private Integer contextWindowTokens;
    private String reasoningEffort;
    private String reasoningEffortLabel;
    private boolean imageInputSupported;
    private boolean personal;
    private boolean available;
    private String unavailableReason;
}
