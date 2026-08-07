package top.fusb.lingxi.runtime.api.execution;

import java.util.Set;

public record RuntimeCommandOutputDescriptor(
        String type,
        String pathField,
        Set<String> features
) {

    public RuntimeCommandOutputDescriptor {
        features = features == null ? Set.of() : Set.copyOf(features);
    }
}
