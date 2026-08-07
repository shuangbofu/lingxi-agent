package top.fusb.lingxi.runtime.langchain.core;

import top.fusb.lingxi.runtime.api.event.RuntimeEventListener;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionRequest;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionResult;
import top.fusb.lingxi.runtime.api.model.RuntimeSessionRef;
import top.fusb.lingxi.runtime.api.model.RuntimeUsage;
import top.fusb.lingxi.runtime.api.model.RuntimeUsageQuery;

import java.util.Optional;

/**
 * LangChain 执行适配端口。实现方只负责运行时内部执行，不依赖平台任务实体。
 */
public interface LangChainRuntimeDelegate {

    RuntimeExecutionResult execute(RuntimeExecutionRequest request, RuntimeEventListener listener);

    boolean cancel(String executionId);

    long defaultTimeoutSeconds();

    Optional<RuntimeSessionRef> latestSession(String conversationId);

    Optional<RuntimeUsage> readUsage(RuntimeUsageQuery query);
}
