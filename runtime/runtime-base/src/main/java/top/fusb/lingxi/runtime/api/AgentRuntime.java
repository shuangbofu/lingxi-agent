package top.fusb.lingxi.runtime.api;

import top.fusb.lingxi.runtime.api.event.RuntimeEventListener;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionRequest;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionResult;
import top.fusb.lingxi.runtime.api.maintenance.RuntimeMaintenance;
import top.fusb.lingxi.runtime.api.model.RuntimeDescriptor;
import top.fusb.lingxi.runtime.api.model.RuntimeAvailability;
import top.fusb.lingxi.runtime.api.model.RuntimeCostEstimate;
import top.fusb.lingxi.runtime.api.model.RuntimeCostRequest;
import top.fusb.lingxi.runtime.api.model.RuntimeSessionRef;
import top.fusb.lingxi.runtime.api.model.RuntimeUsage;
import top.fusb.lingxi.runtime.api.model.RuntimeUsageQuery;
import top.fusb.lingxi.runtime.api.support.RuntimeCostKit;

import java.util.Optional;

public interface AgentRuntime {

    String code();

    default RuntimeDescriptor descriptor() {
        return new RuntimeDescriptor(code(), code(), null);
    }

    default RuntimeAvailability availability() {
        return RuntimeAvailability.ready();
    }

    RuntimeExecutionResult execute(RuntimeExecutionRequest request, RuntimeEventListener listener);

    boolean cancel(String executionId);

    default long defaultTimeoutSeconds() {
        return 900L;
    }

    default Optional<RuntimeSessionRef> latestSession(String conversationId) {
        return Optional.empty();
    }

    default Optional<RuntimeUsage> readUsage(RuntimeUsageQuery query) {
        return Optional.empty();
    }

    /**
     * 按执行时固化的价格快照估算一次模型调用费用。
     *
     * @param request 模型、调用时间、Token 用量和执行时价格快照
     * @return 价格快照完整时返回费用明细，否则返回空
     */
    default Optional<RuntimeCostEstimate> estimateCost(RuntimeCostRequest request) {
        return RuntimeCostKit.estimate(request);
    }

    default Optional<RuntimeMaintenance> maintenance() {
        return Optional.empty();
    }
}
