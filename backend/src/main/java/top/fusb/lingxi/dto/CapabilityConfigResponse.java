package top.fusb.lingxi.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class CapabilityConfigResponse {

    private Long id;
    private String name;
    private String capabilityCode;
    private String capabilityName;
    private String description;
    private Map<String, Object> config;
    private boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
