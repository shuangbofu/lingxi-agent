package top.fusb.lingxi.definition;

import lombok.Data;

@Data
public class ParameterOptionSourceDefinition {

    private String capabilityCode;
    private String path;
    private String labelField;
    private String valueField;
    private String baseUrlConfigKey = "baseUrl";
    private String tokenConfigKey = "token";
}
