package top.fusb.lingxi.dto;

import top.fusb.lingxi.runtime.api.event.RuntimeActionIcon;
import lombok.Data;

import java.util.List;

@Data
public class CapabilityCommandDefinition {

    private String moduleCode;

    private String code;

    private String name;

    private RuntimeActionIcon icon;

    private String command;

    private String description;

    private List<CapabilityCommandOutputDefinition> outputs = List.of();
}
