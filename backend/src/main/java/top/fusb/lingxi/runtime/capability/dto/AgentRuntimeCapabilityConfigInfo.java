package top.fusb.lingxi.runtime.capability.dto;

import lombok.Data;

import java.util.Map;

@Data
public class AgentRuntimeCapabilityConfigInfo {

    private Long id;
    private String name;
    private String capabilityCode;
    private String capabilityName;
    private String alias;
    private String purpose;
    private String description;
    private Map<String, Object> config;
}
