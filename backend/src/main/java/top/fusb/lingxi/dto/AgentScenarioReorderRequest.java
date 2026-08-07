package top.fusb.lingxi.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class AgentScenarioReorderRequest {

    @NotEmpty
    private List<@NotBlank String> codes;
}
