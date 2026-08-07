package top.fusb.lingxi.runtime.api.model;

public record RuntimeMaintenancePresentation(
        String title,
        String description,
        String installActionLabel,
        String unavailableTitle
) {
}
