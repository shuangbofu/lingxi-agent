package top.fusb.lingxi.runtime.api.execution;

import top.fusb.lingxi.runtime.api.model.RuntimeSessionRef;
import top.fusb.lingxi.runtime.api.model.RuntimeUsage;

import java.time.LocalDateTime;

public record RuntimeExecutionResult(
        int exitCode,
        String stdoutText,
        String stderrText,
        String resultText,
        RuntimeSessionRef session,
        RuntimeUsage usage,
        LocalDateTime engineCompletedAt
) {
}
