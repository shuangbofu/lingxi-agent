package top.fusb.lingxi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AgentDefinitionGuideRequest {

    @NotBlank
    private String key;

    @NotBlank
    private String title;

    private String description;

    @NotBlank
    private String content;

    @NotNull
    private Integer sortOrder;
}
