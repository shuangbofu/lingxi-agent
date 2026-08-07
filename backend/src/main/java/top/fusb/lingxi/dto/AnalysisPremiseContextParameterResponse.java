package top.fusb.lingxi.dto;

import lombok.Data;

import java.util.List;

@Data
public class AnalysisPremiseContextParameterResponse {

    private String key;
    private String name;
    private String type;
    private String description;
    private List<DefinitionParameterOption> options;
    private String defaultValue;
    private String sourceType;
    private String sourceCode;
    private String sourceName;
    private String scenarioCode;
    private String scenarioName;
    private Integer sortOrder;
}
