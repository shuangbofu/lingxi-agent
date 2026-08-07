package top.fusb.lingxi.runtime.codex.core;

import top.fusb.lingxi.runtime.api.event.RuntimeEventListener;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionRequest;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionResult;
import top.fusb.lingxi.runtime.api.model.RuntimeSessionRef;
import top.fusb.lingxi.runtime.api.model.RuntimeUsage;
import top.fusb.lingxi.runtime.api.model.RuntimeUsageQuery;

import java.util.Optional;

public interface CodexRuntimeDelegate {

    RuntimeExecutionResult execute(RuntimeExecutionRequest request, RuntimeEventListener listener);

    boolean cancel(String executionId);

    long defaultTimeoutSeconds();

    Optional<RuntimeSessionRef> latestSession(String conversationId);

    Optional<RuntimeUsage> readUsage(RuntimeUsageQuery query);
}
