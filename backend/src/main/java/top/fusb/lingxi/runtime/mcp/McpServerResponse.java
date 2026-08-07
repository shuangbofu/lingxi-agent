package top.fusb.lingxi.runtime.mcp;

import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpTransport;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpToolPresentation;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
public class McpServerResponse {
    private String id;
    private String code;
    private String name;
    private String instructions;
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
    private boolean enabled;
    private boolean builtin;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
