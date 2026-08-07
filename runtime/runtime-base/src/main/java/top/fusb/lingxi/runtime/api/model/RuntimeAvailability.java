package top.fusb.lingxi.runtime.api.model;

public record RuntimeAvailability(boolean available, String unavailableReason) {

    public static RuntimeAvailability ready() {
        return new RuntimeAvailability(true, null);
    }

    public static RuntimeAvailability unavailable(String reason) {
        return new RuntimeAvailability(false, reason);
    }
}
