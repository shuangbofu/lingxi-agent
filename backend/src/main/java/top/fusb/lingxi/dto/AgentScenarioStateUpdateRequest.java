package top.fusb.lingxi.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;
import java.util.Set;

@Data
public class AgentScenarioStateUpdateRequest {

    @NotNull
    private Boolean enabled;

    @NotNull
    private Boolean userVisible;

    @NotNull
    private Set<String> capabilities;

    @NotNull
    private Map<String, Set<String>> capabilityCommands;
}
