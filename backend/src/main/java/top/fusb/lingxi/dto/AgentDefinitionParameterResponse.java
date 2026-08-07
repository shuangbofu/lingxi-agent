package top.fusb.lingxi.dto;

import lombok.Data;

import java.util.List;

@Data
public class AgentDefinitionParameterResponse {

    private Long id;
    private String key;
    private String name;
    private String type;
    private boolean required;
    private String description;
    private List<DefinitionParameterOption> options;
    private String defaultValue;
    private boolean visible;
    private Integer sortOrder;
}
