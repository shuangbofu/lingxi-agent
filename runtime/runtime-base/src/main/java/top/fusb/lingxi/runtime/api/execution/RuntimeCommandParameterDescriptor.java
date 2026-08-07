package top.fusb.lingxi.runtime.api.execution;

public record RuntimeCommandParameterDescriptor(
        String name,
        String option,
        RuntimeCommandParameterType type,
        boolean required,
        String description
) {
}
