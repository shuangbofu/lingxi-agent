package top.fusb.lingxi.runtime.mcp;

import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpTransport;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpToolPresentation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
public class McpServerRequest {

    private String code;

    @jakarta.validation.constraints.NotBlank(message = "MCP 名称不能为空")
    @Size(max = 120, message = "MCP 名称不能超过 120 个字符")
    private String name;

    @Size(max = 20000, message = "MCP 使用说明不能超过 20000 个字符")
    private String instructions;

    @NotNull(message = "请选择 MCP 连接方式")
    private RuntimeMcpTransport transport;

    private String command;

    private List<String> arguments;

    private String url;

    private Map<String, String> environment;

    private Map<String, String> headers;

    private Set<String> runtimeCodes;

    private Set<String> activationFeatures;

    private Set<String> toolAllowlist;

    private Map<String, RuntimeMcpToolPresentation> toolPresentations;

    private boolean enabled = true;

}
