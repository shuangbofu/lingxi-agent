package top.fusb.lingxi.runtime.api.mcp;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record RuntimeMcpServerConfig(
        String code,
        String name,
        String instructions,
        RuntimeMcpTransport transport,
        String command,
        List<String> arguments,
        String url,
        Map<String, String> environment,
        Map<String, String> headers,
        Set<String> activationFeatures,
        Set<String> toolAllowlist,
        Map<String, RuntimeMcpToolPresentation> toolPresentations
) {

    public RuntimeMcpServerConfig {
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        activationFeatures = activationFeatures == null ? Set.of() : Set.copyOf(activationFeatures);
        toolAllowlist = toolAllowlist == null ? Set.of() : Set.copyOf(toolAllowlist);
        toolPresentations = toolPresentations == null ? Map.of() : Map.copyOf(toolPresentations);
    }
}
