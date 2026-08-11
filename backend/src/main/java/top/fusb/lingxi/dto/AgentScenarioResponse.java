package top.fusb.lingxi.dto;

import top.fusb.lingxi.definition.CapabilityActivationCondition;
import top.fusb.lingxi.enums.ScenarioInputMode;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
public class AgentScenarioResponse {

    private String code;
    private String name;
    private String description;
    private String slogan;
    private String scenario;
    private ScenarioInputMode inputMode;
    private String icon;
    private String iconUrl;
    private String color;
    private String promptText;
    private boolean enabled;
    private boolean uninstallable;
    private boolean userVisible;
    private String packageVersion;
    private Integer sortOrder;
    private boolean uniqueBySource;
    private String resultFormat;
    private String resultRenderer;
    private Set<String> presentations;
    private Integer queuePriority;
    private Set<String> capabilities;
    private Map<String, Set<String>> capabilityCommands;
    private Map<String, CapabilityActivationCondition> capabilityConditions;
    private List<AgentDefinitionParameterResponse> parameters;
    private List<AgentDefinitionGuideResponse> guides;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
