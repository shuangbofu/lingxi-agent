package top.fusb.lingxi.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CapabilityPackageInspectionResponse {

    private String stagingToken;
    private String code;
    private String name;
    private String version;
    private String description;
    private boolean update;
    private String currentVersion;
    private long packageSize;
    private String packageHash;
    private boolean runtimeIncluded;
    private int configParameterCount;
    private int parameterCount;
    private int guideCount;
    private List<String> requirements = new ArrayList<>();
    private List<CapabilityPackageCommandResponse> commands = new ArrayList<>();
}
