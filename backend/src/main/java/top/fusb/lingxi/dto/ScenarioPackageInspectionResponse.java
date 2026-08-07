package top.fusb.lingxi.dto;

import lombok.Data;

import java.util.LinkedHashSet;
import java.util.Set;

@Data
public class ScenarioPackageInspectionResponse {

    private String stagingToken;
    private String code;
    private String name;
    private String version;
    private String description;
    private String color;
    private boolean update;
    private String currentVersion;
    private long packageSize;
    private String packageHash;
    private int parameterCount;
    private Set<String> capabilities = new LinkedHashSet<>();
}
