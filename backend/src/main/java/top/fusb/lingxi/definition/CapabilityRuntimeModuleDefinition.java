package top.fusb.lingxi.definition;

import lombok.Data;

import java.nio.file.Path;
import java.util.List;

@Data
public class CapabilityRuntimeModuleDefinition {

    private String moduleCode;

    private String modulePath;

    private String displayName;

    private String entrypoint;

    private List<CapabilityCommandExtensionDefinition> commands;

    private Path moduleDirectory;
}
