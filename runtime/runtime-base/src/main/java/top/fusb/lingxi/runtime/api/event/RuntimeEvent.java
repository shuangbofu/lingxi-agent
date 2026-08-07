package top.fusb.lingxi.runtime.api.event;

public record RuntimeEvent(
        RuntimeEventType type,
        RuntimeEventStatus status,
        String title,
        String detail,
        RuntimeEventPayload payload
) {
}
