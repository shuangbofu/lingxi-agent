package top.fusb.lingxi.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AgentCapabilityStateUpdateRequest {

    @NotNull
    private Boolean enabled;
}
