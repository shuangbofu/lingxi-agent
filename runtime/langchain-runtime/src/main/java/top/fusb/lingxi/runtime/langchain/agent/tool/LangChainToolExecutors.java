package top.fusb.lingxi.runtime.langchain.agent.tool;

import top.fusb.lingxi.runtime.api.execution.RuntimeToolExecutionMode;
import top.fusb.lingxi.runtime.langchain.agent.LangChainExecutionContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolService;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LangChainToolExecutors {

    static final String ABORTED_RESULT = "{\"status\":\"aborted\",\"message\":\"任务已取消，工具调用已中止。\"}";
    private static final Map<String, RuntimeToolExecutionMode> BUILTIN_POLICIES = Map.ofEntries(
            Map.entry("glob", RuntimeToolExecutionMode.READ_ONLY),
            Map.entry("grep", RuntimeToolExecutionMode.READ_ONLY),
            Map.entry("read_file", RuntimeToolExecutionMode.READ_ONLY),
            Map.entry("query_json", RuntimeToolExecutionMode.READ_ONLY),
            Map.entry("read_skill_file", RuntimeToolExecutionMode.READ_ONLY),
            Map.entry("read_evidence", RuntimeToolExecutionMode.READ_ONLY),
            Map.entry("query_evidence", RuntimeToolExecutionMode.READ_ONLY),
            Map.entry("list_mcp_tools", RuntimeToolExecutionMode.READ_ONLY),
            Map.entry("read_mcp_tool", RuntimeToolExecutionMode.READ_ONLY),
            Map.entry("write_workspace_file", RuntimeToolExecutionMode.SERIAL)
    );
    private static final String DELEGATED_MCP_TOOL = "invoke_mcp_tool";

    private LangChainToolExecutors() {
    }

    /**
     * 将内置 @Tool 对象转换为带执行级并发策略的 ToolExecutor。
     *
     * @param toolObjects 当前执行启用的内置工具对象
     * @param context 当前 LangChain 执行上下文
     * @return 工具规格到受并发策略保护的执行器映射
     * @throws IllegalStateException 工具名称重复时抛出
     */
    public static Map<ToolSpecification, ToolExecutor> create(
            List<Object> toolObjects, LangChainExecutionContext context) {
        Map<ToolSpecification, ToolExecutor> result = new LinkedHashMap<>();
        Set<String> toolNames = new LinkedHashSet<>();
        for (Object toolObject : toolObjects) {
            for (AiServiceTool discoveredTool : ToolService.findTools(toolObject)) {
                ToolSpecification specification = discoveredTool.toolSpecification();
                if (!toolNames.add(specification.name())) {
                    throw new IllegalStateException("内置工具名称重复：" + specification.name());
                }
                ToolExecutor executor = discoveredTool.toolExecutor();
                if (!DELEGATED_MCP_TOOL.equals(specification.name())) {
                    RuntimeToolExecutionMode executionMode = BUILTIN_POLICIES.getOrDefault(
                            specification.name(), RuntimeToolExecutionMode.SERIAL);
                    executor = guarded(executor, executionMode, context);
                }
                result.put(specification, executor);
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    /**
     * 使用当前执行的共享读写门闩包装一个工具执行器。
     *
     * @param delegate 原始 LangChain4j 工具执行器
     * @param executionMode 工具的只读或串行执行语义
     * @param context 当前 LangChain 执行上下文
     * @return 在调用前获取对应执行许可的工具执行器
     * @throws IllegalStateException 等待许可被中断或当前执行已取消时抛出
     */
    public static ToolExecutor guarded(ToolExecutor delegate,
                                       RuntimeToolExecutionMode executionMode,
                                       LangChainExecutionContext context) {
        return new ToolExecutor() {
            @Override
            public String execute(ToolExecutionRequest request, Object memoryId) {
                LangChainExecutionContext.ToolInvocation invocation;
                try {
                    invocation = context.beginToolInvocation(request.id(), request.name());
                } catch (IllegalStateException exception) {
                    return ABORTED_RESULT;
                }
                try (invocation; var ignored = context.acquireToolExecution(executionMode)) {
                    String result = delegate.execute(request, memoryId);
                    if (context.isCancelled() || invocation.isAborted()) {
                        Thread.interrupted();
                        return ABORTED_RESULT;
                    }
                    invocation.success();
                    return result;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    invocation.aborted();
                    Thread.interrupted();
                    return ABORTED_RESULT;
                } catch (RuntimeException exception) {
                    if (context.isCancelled() || invocation.isAborted()
                            || Thread.currentThread().isInterrupted()) {
                        invocation.aborted();
                        Thread.interrupted();
                        return ABORTED_RESULT;
                    }
                    invocation.failed();
                    throw exception;
                }
            }

            @Override
            public ToolExecutionResult executeWithContext(
                    ToolExecutionRequest request, InvocationContext invocationContext) {
                LangChainExecutionContext.ToolInvocation invocation;
                try {
                    invocation = context.beginToolInvocation(request.id(), request.name());
                } catch (IllegalStateException exception) {
                    return abortedResult();
                }
                try (invocation; var ignored = context.acquireToolExecution(executionMode)) {
                    ToolExecutionResult result = delegate.executeWithContext(request, invocationContext);
                    if (context.isCancelled() || invocation.isAborted()) {
                        Thread.interrupted();
                        return abortedResult();
                    }
                    if (result.isError()) {
                        invocation.failed();
                    } else {
                        invocation.success();
                    }
                    return result;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    invocation.aborted();
                    Thread.interrupted();
                    return abortedResult();
                } catch (RuntimeException exception) {
                    if (context.isCancelled() || invocation.isAborted()
                            || Thread.currentThread().isInterrupted()) {
                        invocation.aborted();
                        Thread.interrupted();
                        return abortedResult();
                    }
                    invocation.failed();
                    throw exception;
                }
            }
        };
    }

    private static ToolExecutionResult abortedResult() {
        return ToolExecutionResult.builder()
                .isError(true)
                .resultText(ABORTED_RESULT)
                .build();
    }
}
