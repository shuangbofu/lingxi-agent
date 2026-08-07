package top.fusb.lingxi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class AgentDefinitionParameterRequest {

    @NotBlank
    private String key;

    @NotBlank
    private String name;

    @NotBlank
    private String type;

    @NotNull
    private Boolean required;

    private String description;

    private List<DefinitionParameterOption> options;

    private String defaultValue;

    private Boolean visible;

    @NotNull
    private Integer sortOrder;
}
