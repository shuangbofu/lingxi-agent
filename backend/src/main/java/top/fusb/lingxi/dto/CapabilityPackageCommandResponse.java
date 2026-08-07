package top.fusb.lingxi.dto;

import top.fusb.lingxi.runtime.api.event.RuntimeActionIcon;
import lombok.Data;

@Data
public class CapabilityPackageCommandResponse {

    private String code;
    private String name;
    private RuntimeActionIcon icon;
    private String command;
    private String description;
}
