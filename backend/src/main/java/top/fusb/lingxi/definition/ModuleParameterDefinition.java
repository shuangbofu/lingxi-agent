package top.fusb.lingxi.definition;

import top.fusb.lingxi.dto.DefinitionParameterOption;
import lombok.Data;

import java.util.List;

@Data
public class ModuleParameterDefinition {

    private String key;

    private String name;

    private String type;

    private Boolean required;

    private String description;

    private List<DefinitionParameterOption> options;

    private ParameterOptionSourceDefinition optionSource;

    private String defaultValue;

    private Boolean visible;

    private Integer sortOrder;
}
