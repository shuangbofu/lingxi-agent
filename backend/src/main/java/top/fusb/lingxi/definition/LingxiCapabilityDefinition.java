package top.fusb.lingxi.definition;

import lombok.Data;

import java.util.List;

@Data
public class LingxiCapabilityDefinition {

    private Integer schemaVersion;

    private String version;

    private CapabilityPresentationDefinition presentation;

    private Boolean enabledByDefault;

    private String entrypoint;

    private List<ModuleParameterDefinition> taskParameters;

    private List<ModuleParameterDefinition> configurationParameters;

    private List<ModuleGuideDefinition> guides;

    private List<CapabilityCommandExtensionDefinition> commands;
}
