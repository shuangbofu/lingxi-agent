package top.fusb.lingxi.runtime.api.execution;

import top.fusb.lingxi.runtime.api.capability.RuntimeCapabilityAccess;
import top.fusb.lingxi.runtime.api.model.RuntimeModelConfig;
import top.fusb.lingxi.runtime.api.model.RuntimeSessionRef;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpServerConfig;

import java.util.List;

public record RuntimeExecutionRequest(
        String executionId,
        String conversationId,
        String eventNamespace,
        RuntimeWorkspaceLayout workspace,
        String instructions,
        String taskInstructions,
        String mcpInstructions,
        String prompt,
        String finalResponsePrompt,
        String finalResponseInstructions,
        boolean finalResponseRequired,
        boolean workspaceFileToolsEnabled,
        String principal,
        RuntimeModelConfig modelConfig,
        List<RuntimeCapabilityAccess> capabilities,
        boolean capabilityCommandsRestricted,
        List<RuntimeMcpServerConfig> mcpServers,
        RuntimeExecutionEnvironment environment,
        RuntimeSessionRef resumeSession,
        boolean recovering,
        long timeoutSeconds
) {

    public RuntimeExecutionRequest {
        eventNamespace = eventNamespace == null || eventNamespace.isBlank() ? executionId : eventNamespace;
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
        environment = environment == null ? RuntimeExecutionEnvironment.empty() : environment;
        workspace = java.util.Objects.requireNonNull(workspace, "workspace 不能为空");
        instructions = instructions == null ? "" : instructions;
        taskInstructions = taskInstructions == null ? "" : taskInstructions;
        mcpInstructions = mcpInstructions == null ? "" : mcpInstructions;
        finalResponsePrompt = finalResponsePrompt == null || finalResponsePrompt.isBlank()
                ? prompt : finalResponsePrompt;
        finalResponseInstructions = finalResponseInstructions == null ? "" : finalResponseInstructions;
    }
}
