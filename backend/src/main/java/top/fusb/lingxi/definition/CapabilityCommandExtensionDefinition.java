package top.fusb.lingxi.definition;

import top.fusb.lingxi.dto.CapabilityCommandOutputDefinition;
import top.fusb.lingxi.runtime.api.execution.RuntimeToolExecutionMode;
import lombok.Data;

import java.util.List;

@Data
public class CapabilityCommandExtensionDefinition {

    private String command;

    private String displayName;

    private String icon;

    private String description;

    private List<CapabilityCommandOutputDefinition> outputs = List.of();

    private RuntimeToolExecutionMode executionMode = RuntimeToolExecutionMode.SERIAL;
}
