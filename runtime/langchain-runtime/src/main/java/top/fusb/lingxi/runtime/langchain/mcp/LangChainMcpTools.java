package top.fusb.lingxi.runtime.langchain.mcp;

import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpServerConfig;
import top.fusb.lingxi.runtime.langchain.agent.LangChainExecutionContext;
import top.fusb.lingxi.runtime.langchain.agent.evidence.LangChainEvidenceStore;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class LangChainMcpTools {

    private final Map<String, LangChainMcpRuntime> runtimes;
    private final LangChainExecutionContext context;
    private final LangChainEvidenceStore evidenceStore;
    private final Set<McpToolKey> inspectedTools = ConcurrentHashMap.newKeySet();
    private final Set<String> inspectedServers = ConcurrentHashMap.newKeySet();

    /**
     * 创建仅绑定当前任务 MCP 配置和执行上下文的按需工具网关。
     *
     * @param servers 当前任务挂载的 MCP 配置
     * @param runtimes 与配置顺序一致的 LangChain MCP Runtime
     * @param context 当前 LangChain 执行上下文
     * @param evidenceStore 当前会话的内容寻址证据存储
     * @throws IllegalArgumentException 配置与 Runtime 数量不一致或 MCP 编码重复时抛出
     */
    public LangChainMcpTools(List<RuntimeMcpServerConfig> servers, List<LangChainMcpRuntime> runtimes,
                            LangChainExecutionContext context, LangChainEvidenceStore evidenceStore) {
        if (servers.size() != runtimes.size()) {
            throw new IllegalArgumentException("MCP 配置与 Runtime 数量不一致");
        }
        Map<String, LangChainMcpRuntime> indexed = new LinkedHashMap<>();
        for (int index = 0; index < servers.size(); index++) {
            RuntimeMcpServerConfig server = servers.get(index);
            if (indexed.putIfAbsent(server.code(), runtimes.get(index)) != null) {
                throw new IllegalArgumentException("MCP 编码重复：" + server.code());
            }
        }
        this.runtimes = Map.copyOf(indexed);
        this.context = context;
        this.evidenceStore = evidenceStore;
    }

    /**
     * 列出当前任务挂载的 MCP 服务及允许使用的工具名称，不读取完整参数 Schema。
     *
     * @return 紧凑的 MCP 工具目录
     * @throws IllegalStateException MCP 工具目录无法读取时抛出
     */
    @Tool(name = "list_mcp_tools", value = {
            "列出当前任务可用的 MCP 服务和工具名称。",
            "只返回目录，不加载完整工具参数；准备使用某个工具时再调用 read_mcp_tool。"
    })
    public String listMcpTools() {
        StringBuilder result = new StringBuilder("可用 MCP 工具：\n");
        runtimes.forEach((code, runtime) -> result.append("- ")
                .append(code).append(" (").append(runtime.serverName()).append("): ")
                .append(String.join(", ", runtime.toolNames())).append('\n'));
        return result.append("调用前必须先用 read_mcp_tool 读取目标工具说明。")
                .toString();
    }

    /**
     * 按需读取单个 MCP 工具的服务说明和真实参数 Schema。
     *
     * @param mcpCode list_mcp_tools 返回的 MCP 编码
     * @param toolName list_mcp_tools 返回的工具名称
     * @return 选中 MCP 工具的说明和 JSON Schema
     * @throws IllegalArgumentException MCP 或工具未挂载、未授权时抛出
     * @throws IllegalStateException MCP 服务无法读取时抛出
     */
    @Tool(name = "read_mcp_tool", value = {
            "读取一个 MCP 工具的完整用途、服务说明和参数 JSON Schema。",
            "只有确定任务需要该工具时才读取；读取后才能调用 invoke_mcp_tool。"
    })
    public String readMcpTool(
            @P("MCP 编码") String mcpCode,
            @P("工具名称") String toolName
    ) {
        LangChainMcpRuntime runtime = requireRuntime(mcpCode);
        String normalizedCode = mcpCode == null ? "" : mcpCode.trim();
        boolean includeServerInstructions = inspectedServers.add(normalizedCode);
        String description;
        try {
            description = runtime.describeTool(
                    toolName, includeServerInstructions, context, evidenceStore);
        } catch (RuntimeException exception) {
            if (includeServerInstructions) {
                inspectedServers.remove(normalizedCode);
            }
            throw exception;
        }
        inspectedTools.add(new McpToolKey(mcpCode, toolName));
        return description;
    }

    /**
     * 调用已经通过 read_mcp_tool 读取说明的 MCP 工具。
     *
     * @param mcpCode MCP 编码
     * @param toolName 已读取说明的工具名称
     * @param arguments 严格匹配工具 JSON Schema 的参数对象
     * @return MCP 工具返回的文本或图片内容
     * @throws IllegalArgumentException MCP、工具或参数无效时抛出
     * @throws IllegalStateException 工具尚未读取说明或 MCP 执行失败时抛出
     */
    @Tool(name = "invoke_mcp_tool", value = {
            "调用已经读取过说明的 MCP 工具。",
            "arguments 必须是符合 read_mcp_tool 所返回 JSON Schema 的对象。"
    })
    public List<Content> invokeMcpTool(
            @P("MCP 编码") String mcpCode,
            @P("工具名称") String toolName,
            @P("符合目标工具 Schema 的参数对象") Map<String, Object> arguments
    ) {
        McpToolKey key = new McpToolKey(mcpCode, toolName);
        if (!inspectedTools.contains(key)) {
            throw new IllegalStateException("请先调用 read_mcp_tool 读取 " + mcpCode + "/" + toolName + " 的说明");
        }
        ToolExecutionResult result = requireRuntime(mcpCode).executeTool(
                toolName, arguments == null ? Map.of() : arguments, context, evidenceStore);
        return result.resultContents();
    }

    private LangChainMcpRuntime requireRuntime(String mcpCode) {
        String normalized = mcpCode == null ? "" : mcpCode.trim();
        LangChainMcpRuntime runtime = runtimes.get(normalized);
        if (runtime == null) {
            throw new IllegalArgumentException("当前任务未挂载 MCP：" + normalized);
        }
        return runtime;
    }

    private record McpToolKey(String mcpCode, String toolName) {
        private McpToolKey {
            mcpCode = mcpCode == null ? "" : mcpCode.trim();
            toolName = toolName == null ? "" : toolName.trim();
        }
    }
}
