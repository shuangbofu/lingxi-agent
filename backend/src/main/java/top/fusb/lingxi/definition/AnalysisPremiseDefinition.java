package top.fusb.lingxi.definition;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AnalysisPremiseDefinition {

    private String code;
    private String name;
    private String description;
    private Map<String, String> contextValues = new LinkedHashMap<>();
    private String promptText;
    private Integer sortOrder = 0;
}
