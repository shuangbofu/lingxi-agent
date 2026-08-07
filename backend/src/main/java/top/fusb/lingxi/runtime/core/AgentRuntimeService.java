package top.fusb.lingxi.runtime.core;

import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.runtime.api.event.RuntimeEventListener;
import top.fusb.lingxi.runtime.api.model.RuntimeDescriptor;
import top.fusb.lingxi.runtime.api.model.RuntimeAvailability;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionRequest;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionResult;
import top.fusb.lingxi.runtime.api.maintenance.RuntimeMaintenance;
import top.fusb.lingxi.runtime.api.maintenance.RuntimeMaintenanceOperation;
import top.fusb.lingxi.runtime.api.maintenance.RuntimeMaintenanceStatus;
import top.fusb.lingxi.runtime.api.model.RuntimeSessionRef;
import top.fusb.lingxi.runtime.api.model.RuntimeUsage;
import top.fusb.lingxi.runtime.api.model.RuntimeUsageQuery;
import top.fusb.lingxi.runtime.api.model.RuntimeCostEstimate;
import top.fusb.lingxi.runtime.api.model.RuntimeCostRequest;
import top.fusb.lingxi.runtime.api.support.RuntimeSensitiveText;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class AgentRuntimeService {

    private final AgentRuntimeRegistry runtimeRegistry;

    public AgentRuntimeService(AgentRuntimeRegistry runtimeRegistry) {
        this.runtimeRegistry = runtimeRegistry;
    }

    public RuntimeExecutionResult execute(String runtimeCode,
                                          RuntimeExecutionRequest request,
                                          RuntimeEventListener listener) {
        RuntimeEventListener secureListener = new RuntimeEventListener() {
            @Override
            public void onEvent(top.fusb.lingxi.runtime.api.event.RuntimeEvent event) {
                listener.onEvent(RuntimeSensitiveText.redact(event, request.environment().sensitiveValues()));
            }

            @Override
            public void onMessageDelta(top.fusb.lingxi.runtime.api.model.RuntimeMessageDelta delta) {
                listener.onMessageDelta(RuntimeSensitiveText.redact(delta, request.environment().sensitiveValues()));
            }

            @Override
            public void onUsage(RuntimeUsage usage) {
                listener.onUsage(usage);
            }
        };
        RuntimeExecutionResult result = runtimeRegistry.require(runtimeCode).execute(request, secureListener);
        return RuntimeSensitiveText.redact(result, request.environment().sensitiveValues());
    }

    public boolean cancel(String runtimeCode, String executionId) {
        return runtimeRegistry.require(runtimeCode).cancel(executionId);
    }

    public long defaultTimeoutSeconds(String runtimeCode) {
        return runtimeRegistry.require(runtimeCode).defaultTimeoutSeconds();
    }

    public Optional<RuntimeSessionRef> latestSession(String runtimeCode, String conversationId) {
        return runtimeRegistry.require(runtimeCode).latestSession(conversationId);
    }

    public Optional<RuntimeUsage> readUsage(String runtimeCode, RuntimeUsageQuery query) {
        return runtimeRegistry.require(runtimeCode).readUsage(query);
    }

    public Optional<RuntimeCostEstimate> estimateCost(String runtimeCode, RuntimeCostRequest request) {
        return runtimeRegistry.require(runtimeCode).estimateCost(request);
    }

    public List<RuntimeDescriptor> descriptors() {
        return runtimeRegistry.descriptors();
    }

    /**
     * 查询指定 Runtime 对平台公开的描述信息。
     *
     * @param runtimeCode Runtime 编码
     * @return Runtime 已注册时返回描述信息，否则返回空
     */
    public Optional<RuntimeDescriptor> descriptor(String runtimeCode) {
        return runtimeRegistry.descriptors().stream()
                .filter(descriptor -> descriptor.code().equals(runtimeCode))
                .findFirst();
    }

    public String requireCode(String runtimeCode) {
        return runtimeRegistry.require(runtimeCode).code();
    }

    /**
     * 查询 Runtime 注册表选出的默认实现编码。
     *
     * @return 默认 Runtime 编码
     * @throws IllegalStateException 没有注册任何 Runtime 时抛出
     */
    public String defaultCode() {
        return runtimeRegistry.defaultCode();
    }

    public RuntimeAvailability availability(String runtimeCode) {
        return runtimeRegistry.require(runtimeCode).availability();
    }

    public RuntimeMaintenanceStatus maintenanceStatus(String runtimeCode) {
        return maintenance(runtimeCode).status();
    }

    public RuntimeMaintenanceOperation startInstall(String runtimeCode) {
        try {
            return maintenance(runtimeCode).startInstall();
        } catch (BizException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BizException(ErrorCode.PROCESS_ERROR, ErrorSubCode.RUNTIME_MAINTENANCE_FAILED,
                    exception.getMessage());
        }
    }

    public RuntimeMaintenanceOperation installStatus(String runtimeCode) {
        return maintenance(runtimeCode).installStatus();
    }

    private RuntimeMaintenance maintenance(String runtimeCode) {
        return runtimeRegistry.require(runtimeCode).maintenance()
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_ERROR,
                        ErrorSubCode.RUNTIME_MAINTENANCE_NOT_SUPPORTED));
    }
}
