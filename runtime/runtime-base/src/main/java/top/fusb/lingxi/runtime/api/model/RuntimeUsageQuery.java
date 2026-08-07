package top.fusb.lingxi.runtime.api.model;

import java.time.LocalDateTime;

public record RuntimeUsageQuery(
        String executionId,
        String conversationId,
        LocalDateTime startedAt,
        LocalDateTime endedAt
) {
}
