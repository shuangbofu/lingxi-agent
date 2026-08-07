package top.fusb.lingxi.definition;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import top.fusb.lingxi.enums.ScenarioInputMode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
public class ModuleDefinition {

    private String code;

    private String version;

    private String name;

    private String description;

    private String slogan;

    private String scenario;

    private ScenarioInputMode inputMode;

    private String icon;

    private String color;

    private String promptFile;

    private String runtimePath;

    private Boolean enabled;

    private Boolean userVisible;

    private Integer sortOrder;

    private Boolean uniqueBySource;

    private String resultFormat;

    private String resultRenderer;

    private Set<String> presentations;

    private Integer queuePriority;

    private Set<String> capabilities;

    private Map<String, Set<String>> capabilityCommands;

    private Map<String, CapabilityActivationCondition> capabilityConditions;

    private List<ModuleParameterDefinition> config;

    private List<ModuleParameterDefinition> parameters;

    private List<ModuleGuideDefinition> guides;

    @JsonIgnore
    private String modulePath;

    @JsonIgnore
    private Path moduleDirectory;

    @JsonIgnore
    private boolean installed;
}
