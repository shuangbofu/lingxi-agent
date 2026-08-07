package top.fusb.lingxi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class CapabilityConfigSaveRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String capabilityCode;

    private String description;

    private Map<String, Object> config;

    @NotNull
    private Boolean enabled;
}
