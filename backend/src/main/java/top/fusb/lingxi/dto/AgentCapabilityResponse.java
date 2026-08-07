package top.fusb.lingxi.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class AgentCapabilityResponse {

    private String code;
    private String name;
    private String description;
    private String icon;
    private String iconUrl;
    private String promptText;
    private boolean enabled;
    private String packageVersion;
    private List<AgentDefinitionParameterResponse> configParameters;
    private List<AgentDefinitionParameterResponse> parameters;
    private List<AgentDefinitionGuideResponse> guides;
    private List<CapabilityCommandDefinition> commands;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
