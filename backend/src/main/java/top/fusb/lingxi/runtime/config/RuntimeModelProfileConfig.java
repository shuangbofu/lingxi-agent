package top.fusb.lingxi.runtime.config;

import top.fusb.lingxi.runtime.api.model.RuntimeModelProtocol;
import lombok.Data;

@Data
public class RuntimeModelProfileConfig {

    private String id;
    private String name;
    private String description;
    private String providerId;
    private String providerName;
    private String providerType;
    // 运行时使用供应商配置展开连接参数，不由模型配置重复维护。
    private String baseUrl;
    private String apiKey;
    private String model;
    private RuntimeModelProtocol protocol;
    private Integer contextWindowTokens;
    private String reasoningEffort;
    private String thinkingFieldName;
    private String instructionPrompt;
    private Integer maxConcurrency;
    private boolean imageInputSupported;
    private boolean enabled = true;
    private Integer sortOrder;
}
