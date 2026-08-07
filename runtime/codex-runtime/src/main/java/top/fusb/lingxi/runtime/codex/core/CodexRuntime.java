package top.fusb.lingxi.runtime.codex.core;

import top.fusb.lingxi.runtime.api.AgentRuntime;
import top.fusb.lingxi.runtime.api.model.RuntimeDescriptor;
import top.fusb.lingxi.runtime.api.model.RuntimeAvailability;
import top.fusb.lingxi.runtime.api.model.RuntimeModelProtocol;
import top.fusb.lingxi.runtime.api.model.RuntimeMaintenancePresentation;
import top.fusb.lingxi.runtime.api.event.RuntimeEventListener;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionRequest;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionResult;
import top.fusb.lingxi.runtime.api.maintenance.RuntimeMaintenance;
import top.fusb.lingxi.runtime.api.maintenance.RuntimeMaintenanceStatus;
import top.fusb.lingxi.runtime.api.model.RuntimeSessionRef;
import top.fusb.lingxi.runtime.api.model.RuntimeUsage;
import top.fusb.lingxi.runtime.api.model.RuntimeUsageQuery;
import top.fusb.lingxi.runtime.codex.maintenance.CodexCliManager;

import java.util.Optional;
import java.util.Set;

public class CodexRuntime implements AgentRuntime {

    public static final String CODE = "codex";

    private final CodexRuntimeDelegate delegate;
    private final CodexCliManager cliManager;

    public CodexRuntime(CodexRuntimeDelegate delegate, CodexCliManager cliManager) {
        this.delegate = delegate;
        this.cliManager = cliManager;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public RuntimeDescriptor descriptor() {
        return new RuntimeDescriptor(CODE, "Codex", "自主规划稳定，内置工具丰富，面对复杂问题可灵活调整分析路径。",
                "/runtime-icons/codex-mark.png", false, true, Set.of(RuntimeModelProtocol.RESPONSES), true,
                new RuntimeMaintenancePresentation(
                        "Codex CLI 维护", "维护本机执行引擎的安装状态和版本。", "安装 Codex CLI", "Codex CLI 不可用"),
                "逐次耗时来自 Runtime session 原生事件；可比较每轮总耗时和 Token，但首个可观测响应不等于精确首 Token，不能据此单独判断网络性能。",
                100);
    }

    @Override
    public RuntimeAvailability availability() {
        RuntimeMaintenanceStatus status = cliManager.executionStatus();
        if (status.isAvailable()) {
            return RuntimeAvailability.ready();
        }
        String reason = status.getErrorText() == null || status.getErrorText().isBlank()
                ? "Codex CLI 未安装或不可用"
                : status.getErrorText();
        return RuntimeAvailability.unavailable(reason);
    }

    @Override
    public RuntimeExecutionResult execute(RuntimeExecutionRequest request, RuntimeEventListener listener) {
        return delegate.execute(request, listener);
    }

    @Override
    public boolean cancel(String executionId) {
        return delegate.cancel(executionId);
    }

    @Override
    public long defaultTimeoutSeconds() {
        return delegate.defaultTimeoutSeconds();
    }

    @Override
    public Optional<RuntimeSessionRef> latestSession(String conversationId) {
        return delegate.latestSession(conversationId);
    }

    @Override
    public Optional<RuntimeUsage> readUsage(RuntimeUsageQuery query) {
        return delegate.readUsage(query);
    }

    @Override
    public Optional<RuntimeMaintenance> maintenance() {
        return Optional.of(cliManager);
    }
}
