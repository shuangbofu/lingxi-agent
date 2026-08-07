package top.fusb.lingxi.runtime.api.capability;

import java.util.Set;

public record RuntimeCapabilityAccess(String code, Set<String> commandCodes) {

    public RuntimeCapabilityAccess {
        commandCodes = commandCodes == null ? Set.of() : Set.copyOf(commandCodes);
    }
}
