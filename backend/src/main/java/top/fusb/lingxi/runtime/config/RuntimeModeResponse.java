package top.fusb.lingxi.runtime.config;

import lombok.Data;

import java.util.List;
import java.util.Set;
import top.fusb.lingxi.runtime.api.model.RuntimeModelProtocol;
import top.fusb.lingxi.runtime.api.model.RuntimeMaintenancePresentation;

@Data
public class RuntimeModeResponse {

    private String code;
    private String name;
    private String description;
    private String iconUrl;
    private boolean messageStreamingSupported;
    private boolean maintenanceSupported;
    private boolean mcpSupported;
    private boolean available;
    private String unavailableReason;
    private boolean defaultSelected;
    private List<RuntimeModelOptionResponse> models;
    private List<RuntimeProviderTypeOption> modelProviderTypes;
    private Set<RuntimeModelProtocol> supportedModelProtocols;
    private RuntimeMaintenancePresentation maintenancePresentation;
    private String modelTimingNote;
}
