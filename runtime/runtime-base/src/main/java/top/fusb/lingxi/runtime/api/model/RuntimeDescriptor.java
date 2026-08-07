package top.fusb.lingxi.runtime.api.model;

import java.util.Set;

public record RuntimeDescriptor(
        String code,
        String name,
        String description,
        String iconUrl,
        boolean messageStreamingSupported,
        boolean maintenanceSupported,
        Set<RuntimeModelProtocol> supportedModelProtocols,
        boolean mcpSupported,
        RuntimeMaintenancePresentation maintenancePresentation,
        String modelTimingNote,
        int defaultPriority
) {

    public RuntimeDescriptor {
        supportedModelProtocols = supportedModelProtocols == null
                ? Set.of(RuntimeModelProtocol.RESPONSES)
                : Set.copyOf(supportedModelProtocols);
    }

    public RuntimeDescriptor(String code, String name, String description) {
        this(code, name, description, null, false, false, Set.of(RuntimeModelProtocol.RESPONSES), false,
                null, null, 0);
    }

    public RuntimeDescriptor(String code, String name, String description, boolean messageStreamingSupported) {
        this(code, name, description, null, messageStreamingSupported, false,
                Set.of(RuntimeModelProtocol.RESPONSES), false, null, null, 0);
    }

    public RuntimeDescriptor(String code, String name, String description, boolean messageStreamingSupported,
                             boolean maintenanceSupported) {
        this(code, name, description, null, messageStreamingSupported, maintenanceSupported,
                Set.of(RuntimeModelProtocol.RESPONSES), false, null, null, 0);
    }

    public RuntimeDescriptor(String code, String name, String description, boolean messageStreamingSupported,
                             boolean maintenanceSupported, Set<RuntimeModelProtocol> supportedModelProtocols) {
        this(code, name, description, null, messageStreamingSupported, maintenanceSupported,
                supportedModelProtocols, false, null, null, 0);
    }

    public RuntimeDescriptor(String code, String name, String description, String iconUrl,
                             boolean messageStreamingSupported, boolean maintenanceSupported) {
        this(code, name, description, iconUrl, messageStreamingSupported, maintenanceSupported,
                Set.of(RuntimeModelProtocol.RESPONSES), false, null, null, 0);
    }

    public RuntimeDescriptor(String code, String name, String description, String iconUrl,
                             boolean messageStreamingSupported, boolean maintenanceSupported,
                             Set<RuntimeModelProtocol> supportedModelProtocols) {
        this(code, name, description, iconUrl, messageStreamingSupported, maintenanceSupported,
                supportedModelProtocols, false, null, null, 0);
    }
}
