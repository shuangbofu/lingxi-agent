package top.fusb.lingxi.runtime.capability.dto;

import lombok.Data;

@Data
public class AgentRuntimeCapabilityConfigSummary {

    private Long id;
    private String name;
    private String capabilityCode;
    private String capabilityName;
    private String alias;
    private String purpose;
    private String description;
    private boolean runtime;
}
