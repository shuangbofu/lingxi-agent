package top.fusb.lingxi.runtime.langchain.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import top.fusb.lingxi.runtime.api.event.RuntimeActionIcon;
import top.fusb.lingxi.runtime.api.execution.RuntimeToolExecutionMode;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpServerConfig;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpToolPresentation;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpTransport;
import top.fusb.lingxi.runtime.langchain.agent.LangChainExecutionContext;
import top.fusb.lingxi.runtime.langchain.agent.evidence.LangChainEvidenceStore;
import top.fusb.lingxi.runtime.langchain.agent.tool.LangChainToolExecutors;
import top.fusb.lingxi.runtime.langchain.config.LangChainRuntimeProperties;
import top.fusb.lingxi.runtime.langchain.util.LangChainToolOutputKit;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpToolMetadataKeys;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public final class LangChainMcpRuntime implements AutoCloseable {

    private final LangChainRuntimeProperties properties;
    private final ObjectMapper objectMapper;
    private final RuntimeMcpServerConfig server;
    private final String command;
    private final Map<String, Map<ToolCallSignature, Integer>> executionToolCalls = new ConcurrentHashMap<>();
    private final AtomicLong gatewayCallSequence = new AtomicLong();
    private McpClient client;
    private McpToolProvider toolProvider;

    public LangChainMcpRuntime(LangChainRuntimeProperties properties, ObjectMapper objectMapper,
                               RuntimeMcpServerConfig server) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.server = server;
        this.command = server.transport() == RuntimeMcpTransport.STDIO
                ? resolveCommand(server.command()) : null;
    }

    /**
     * 返回绑定当前执行上下文的通用 MCP 工具提供器。
     *
     * @param context 当前 LangChain 执行上下文
     * @param evidenceStore 当前会话的内容寻址证据存储
     * @return 带重复调用保护和大结果转存能力的 MCP 工具提供器
     * @throws IllegalStateException MCP 服务无法连接时抛出
     */
    public synchronized ToolProvider toolProvider(LangChainExecutionContext context,
                                                  LangChainEvidenceStore evidenceStore) {
        McpToolProvider delegate = delegateToolProvider();
        return new ToolProvider() {
            @Override
            public ToolProviderResult provideTools(ToolProviderRequest request) {
                ToolProviderResult provided = delegate.provideTools(request);
                Map<dev.langchain4j.agent.tool.ToolSpecification, ToolExecutor> guarded = new LinkedHashMap<>();
                provided.tools().forEach((specification, executor) -> {
                    RuntimeMcpToolPresentation configured = server.toolPresentations().get(specification.name());
                    RuntimeToolExecutionMode executionMode = toolExecutionMode(specification, configured);
                    context.registerToolPresentation(
                            specification.name(), toolDisplayName(specification, configured),
                            configured == null || configured.icon() == null
                                    ? RuntimeActionIcon.PLUGS_CONNECTED : configured.icon());
                    ToolExecutor guardedExecutor = new ToolExecutor() {
                        @Override
                        public String execute(ToolExecutionRequest toolRequest, Object memoryId) {
                            context.beginToolCall(toolRequest.name());
                            return executor.execute(toolRequest, memoryId);
                        }

                        @Override
                        public ToolExecutionResult executeWithContext(
                                ToolExecutionRequest toolRequest,
                                dev.langchain4j.invocation.InvocationContext invocationContext
                        ) {
                            context.beginToolCall(toolRequest.name());
                            ToolCallSignature signature = signature(toolRequest);
                            int count = executionToolCalls
                                    .computeIfAbsent(context.executionId(), ignored -> new ConcurrentHashMap<>())
                                    .merge(signature, 1, Integer::sum);
                            if (count >= properties.getMcp().getDuplicateToolCallLimit()) {
                                return ToolExecutionResult.builder()
                                            .isError(true)
                                            .resultText("检测到相同 MCP 工具和参数被重复调用，请调整参数或根据已有结果继续。")
                                            .build();
                            }
                            return limitToolOutput(
                                    context,
                                    evidenceStore,
                                    toolRequest,
                                    executor.executeWithContext(toolRequest, invocationContext)
                            );
                        }
                    };
                    guarded.put(specification, LangChainToolExecutors.guarded(
                            guardedExecutor, executionMode, context));
                });
                return ToolProviderResult.builder()
                        .addAll(guarded)
                        .immediateReturnToolNames(provided.immediateReturnToolNames())
                        .build();
            }

            @Override
            public boolean isDynamic() {
                return delegate.isDynamic();
            }
        };
    }

    /**
     * 返回 MCP 服务展示名称。
     *
     * @return MCP 服务展示名称
     */
    public String serverName() {
        return server.name();
    }

    /**
     * 列出当前服务允许模型使用的工具名称，不在配置已有 Allowlist 时连接 MCP 服务。
     *
     * @return 已授权工具名称
     * @throws IllegalStateException 未配置 Allowlist 且 MCP 服务无法连接时抛出
     */
    public List<String> toolNames() {
        if (!server.toolAllowlist().isEmpty()) {
            return server.toolAllowlist().stream().sorted().toList();
        }
        return delegateToolProvider().provideTools(null).tools().keySet().stream()
                .map(dev.langchain4j.agent.tool.ToolSpecification::name)
                .sorted()
                .toList();
    }

    /**
     * 按需读取一个已授权 MCP 工具的说明和参数 Schema。
     *
     * @param toolName MCP 工具名称
     * @param includeServerInstructions 是否包含当前 MCP 的通用服务说明
     * @param context 当前 LangChain 执行上下文
     * @param evidenceStore 当前会话的内容寻址证据存储
     * @return 包含服务说明和工具 Schema 的文本
     * @throws IllegalArgumentException 工具未授权或不存在时抛出
     * @throws IllegalStateException MCP 服务无法连接时抛出
     */
    public String describeTool(String toolName, boolean includeServerInstructions, LangChainExecutionContext context,
                               LangChainEvidenceStore evidenceStore) {
        String normalized = requireAllowedTool(toolName);
        var specification = toolProvider(context, evidenceStore).provideTools(null)
                .toolSpecificationByName(normalized);
        if (specification == null) {
            throw new IllegalArgumentException("MCP " + server.code() + " 不存在工具：" + normalized);
        }
        StringBuilder result = new StringBuilder()
                .append("MCP: ").append(server.code()).append(" (").append(server.name()).append(")\n")
                .append("工具: ").append(normalized).append('\n');
        if (includeServerInstructions && server.instructions() != null && !server.instructions().isBlank()) {
            result.append("服务说明:\n").append(server.instructions().trim()).append('\n');
        }
        return result.append("工具定义:\n").append(specification.toJson()).toString();
    }

    /**
     * 通过真实 MCP ToolExecutor 调用一个已授权工具。
     *
     * @param toolName MCP 工具名称
     * @param arguments 匹配工具 Schema 的结构化参数
     * @param context 当前 LangChain 执行上下文
     * @param evidenceStore 当前会话的内容寻址证据存储
     * @return 保留文本、图片和错误状态的 MCP 工具结果
     * @throws IllegalArgumentException 工具未授权、不存在或参数无法序列化时抛出
     * @throws IllegalStateException MCP 返回错误时抛出
     */
    public ToolExecutionResult executeTool(String toolName, Map<String, Object> arguments,
                                           LangChainExecutionContext context, LangChainEvidenceStore evidenceStore) {
        String normalized = requireAllowedTool(toolName);
        ToolProviderResult provided = toolProvider(context, evidenceStore).provideTools(null);
        ToolExecutor executor = provided.toolExecutorByName(normalized);
        if (executor == null) {
            throw new IllegalArgumentException("MCP " + server.code() + " 不存在工具：" + normalized);
        }
        String serializedArguments;
        try {
            serializedArguments = objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (Exception exception) {
            throw new IllegalArgumentException("MCP 工具参数无法序列化：" + exception.getMessage(), exception);
        }
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("mcp-gateway-" + gatewayCallSequence.incrementAndGet())
                .name(normalized)
                .arguments(serializedArguments)
                .build();
        ToolExecutionResult result;
        try {
            result = executor.executeWithContext(request, null);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("MCP " + server.code() + "/" + normalized
                    + " 调用失败：" + exception.getMessage(), exception);
        }
        if (result.isError()) {
            throw new IllegalStateException("MCP " + server.code() + "/" + normalized
                    + " 执行失败：" + result.resultText());
        }
        return result;
    }

    /**
     * 释放单次执行的 MCP 重复调用统计。
     *
     * @param executionId 平台执行 ID
     * @return 无返回值
     */
    public void cleanupExecution(String executionId) {
        executionToolCalls.remove(executionId);
    }

    @Override
    public synchronized void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception exception) {
                log.warn("Failed to close LangChain MCP client", exception);
            }
        }
        client = null;
        toolProvider = null;
        executionToolCalls.clear();
    }

    ToolExecutionResult limitToolOutput(LangChainExecutionContext context,
                                        LangChainEvidenceStore evidenceStore,
                                        ToolExecutionRequest request,
                                        ToolExecutionResult result) {
        if (result == null || result.resultContents().size() != 1
                || !(result.resultContents().get(0) instanceof TextContent textContent)) {
            return result;
        }
        String output = textContent.text();
        int limit = Math.max(1_000, properties.getToolOutputMaxChars());
        if (output == null || output.length() <= limit) {
            return result;
        }
        try {
            LangChainEvidenceStore.EvidenceReference evidence = evidenceStore.record(
                    request.name(), request.arguments(), output);
            if (evidence.duplicate()) {
                return ToolExecutionResult.builder()
                        .isError(result.isError())
                        .resultText(objectMapper.writeValueAsString(evidenceStore.duplicateResult(evidence)))
                        .build();
            }
            JsonNode parsed = null;
            try {
                parsed = objectMapper.readTree(output);
            } catch (Exception ignored) {
                // 非结构化 MCP 结果使用通用文本预览。
            }
            ObjectNode response = LangChainToolOutputKit.summarize(
                    objectMapper,
                    output,
                    parsed,
                    null,
                    Math.min(limit, Math.max(0, properties.getToolOutputPreviewChars()))
            );
            evidenceStore.annotate(response, evidence);
            return ToolExecutionResult.builder()
                    .isError(result.isError())
                    .resultText(objectMapper.writeValueAsString(response))
                    .build();
        } catch (Exception exception) {
            log.warn("Failed to externalize oversized MCP tool output tool={} executionId={}",
                    request.name(), context.executionId(), exception);
            return ToolExecutionResult.builder()
                    .isError(result.isError())
                    .resultText(output.substring(0, limit) + "\n\n[结果过长，已截断]")
                    .build();
        }
    }

    private synchronized McpToolProvider delegateToolProvider() {
        if (toolProvider == null) {
            var builder = McpToolProvider.builder()
                    .mcpClients(client())
                    .failIfOneServerFails(true);
            if (!server.toolAllowlist().isEmpty()) {
                builder.filterToolNames(server.toolAllowlist().toArray(String[]::new));
            }
            toolProvider = builder.build();
        }
        return toolProvider;
    }

    private String requireAllowedTool(String toolName) {
        String normalized = toolName == null ? "" : toolName.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("MCP 工具名称不能为空");
        }
        Set<String> allowlist = server.toolAllowlist();
        if (!allowlist.isEmpty() && !allowlist.contains(normalized)) {
            throw new IllegalArgumentException("MCP " + server.code() + " 未授权工具：" + normalized);
        }
        return normalized;
    }

    String toolDisplayName(dev.langchain4j.agent.tool.ToolSpecification specification,
                           RuntimeMcpToolPresentation configured) {
        if (configured != null && configured.label() != null && !configured.label().isBlank()) {
            return configured.label().trim();
        }
        Object directTitle = specification.metadata().get(McpToolMetadataKeys.TITLE);
        if (directTitle instanceof String title && !title.isBlank()) {
            return title.trim();
        }
        Object annotationTitle = specification.metadata().get(McpToolMetadataKeys.TITLE_ANNOTATION);
        if (annotationTitle instanceof String title && !title.isBlank()) {
            return title.trim();
        }
        return specification.name();
    }

    RuntimeToolExecutionMode toolExecutionMode(
            dev.langchain4j.agent.tool.ToolSpecification specification,
            RuntimeMcpToolPresentation configured) {
        if (configured != null && configured.executionMode() != null) {
            return configured.executionMode();
        }
        return Boolean.TRUE.equals(specification.metadata().get(McpToolMetadataKeys.READ_ONLY_HINT))
                ? RuntimeToolExecutionMode.READ_ONLY : RuntimeToolExecutionMode.SERIAL;
    }

    private synchronized McpClient client() {
        if (client != null) {
            return client;
        }
        try {
            McpTransport transport;
            if (server.transport() == RuntimeMcpTransport.STDIO) {
                List<String> processCommand = new ArrayList<>();
                processCommand.add(command);
                processCommand.addAll(server.arguments());
                transport = StdioMcpTransport.builder()
                        .command(processCommand)
                        .environment(server.environment())
                        .logEvents(properties.getMcp().isLogEvents())
                        .build();
            } else {
                transport = StreamableHttpMcpTransport.builder()
                        .url(server.url())
                        .customHeaders(server.headers())
                        .timeout(properties.getMcp().getRequestTimeout())
                        .logRequests(properties.getMcp().isLogEvents())
                        .logResponses(properties.getMcp().isLogEvents())
                        .build();
            }
            client = DefaultMcpClient.builder()
                    .key("lingxi-" + server.code())
                    .transport(transport)
                    .build();
            log.info("LangChain MCP client started code={} transport={}", server.code(), server.transport());
            return client;
        } catch (Exception exception) {
            throw new IllegalStateException("无法连接 MCP " + server.name() + "：" + exception.getMessage(), exception);
        }
    }

    private ToolCallSignature signature(ToolExecutionRequest request) {
        JsonNode arguments;
        try {
            arguments = objectMapper.readTree(request.arguments());
        } catch (Exception exception) {
            arguments = objectMapper.getNodeFactory().textNode(request.arguments());
        }
        return new ToolCallSignature(request.name(), arguments);
    }

    private String resolveCommand(String configuredCommand) {
        String configured = configuredCommand == null ? "" : configuredCommand.trim();
        if (configured.isBlank()) {
            throw new IllegalArgumentException("MCP 启动命令不能为空");
        }
        if (!configured.contains("/") && !configured.contains("\\")) {
            return configured;
        }
        Path path = Path.of(configured);
        if (path.isAbsolute()) {
            return path.normalize().toString();
        }
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path local = workingDirectory.resolve(path).normalize();
        if (Files.isRegularFile(local)) {
            return local.toString();
        }
        Path parent = workingDirectory.resolve("..").resolve(path).normalize();
        return Files.isRegularFile(parent) ? parent.toString() : local.toString();
    }

    private record ToolCallSignature(String toolName, JsonNode arguments) {
    }
}
