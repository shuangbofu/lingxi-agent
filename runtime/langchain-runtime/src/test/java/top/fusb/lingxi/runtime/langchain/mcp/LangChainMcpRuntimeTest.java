package top.fusb.lingxi.runtime.langchain.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.runtime.api.event.RuntimeEventListener;
import top.fusb.lingxi.runtime.api.event.RuntimeActionIcon;
import top.fusb.lingxi.runtime.api.execution.RuntimeToolExecutionMode;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpServerConfig;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpToolPresentation;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpTransport;
import top.fusb.lingxi.runtime.langchain.agent.LangChainExecutionContext;
import top.fusb.lingxi.runtime.langchain.agent.evidence.LangChainEvidenceStore;
import top.fusb.lingxi.runtime.langchain.config.LangChainRuntimeProperties;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.mcp.client.McpToolMetadataKeys;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangChainMcpRuntimeTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesMcpToolPresentationFromConfigThenStandardMetadata() {
        LangChainMcpRuntime runtime = new LangChainMcpRuntime(
                new LangChainRuntimeProperties(), new ObjectMapper(), server());
        ToolSpecification specification = ToolSpecification.builder()
                .name("list_projects")
                .parameters(JsonObjectSchema.builder().build())
                .addMetadata(McpToolMetadataKeys.TITLE_ANNOTATION, "List projects")
                .build();

        assertThat(runtime.toolDisplayName(specification, null)).isEqualTo("List projects");
        assertThat(runtime.toolDisplayName(specification,
                new RuntimeMcpToolPresentation("查看索引项目", RuntimeActionIcon.CODE)))
                .isEqualTo("查看索引项目");
    }

    @Test
    void resolvesMcpToolExecutionModeFromExplicitConfigThenReadOnlyHint() {
        LangChainMcpRuntime runtime = new LangChainMcpRuntime(
                new LangChainRuntimeProperties(), new ObjectMapper(), server());
        ToolSpecification readOnly = ToolSpecification.builder()
                .name("search_code")
                .parameters(JsonObjectSchema.builder().build())
                .addMetadata(McpToolMetadataKeys.READ_ONLY_HINT, true)
                .build();
        ToolSpecification unspecified = ToolSpecification.builder()
                .name("update_index")
                .parameters(JsonObjectSchema.builder().build())
                .build();

        assertThat(runtime.toolExecutionMode(readOnly, null))
                .isEqualTo(RuntimeToolExecutionMode.READ_ONLY);
        assertThat(runtime.toolExecutionMode(unspecified, null))
                .isEqualTo(RuntimeToolExecutionMode.SERIAL);
        assertThat(runtime.toolExecutionMode(readOnly,
                new RuntimeMcpToolPresentation(
                        "搜索代码", RuntimeActionIcon.CODE, RuntimeToolExecutionMode.SERIAL)))
                .isEqualTo(RuntimeToolExecutionMode.SERIAL);
    }

    @Test
    void externalizesOversizedMcpOutputWithoutDependingOnToolSemantics() throws Exception {
        LangChainRuntimeProperties properties = new LangChainRuntimeProperties();
        properties.setToolOutputMaxChars(1_000);
        RuntimeMcpServerConfig server = new RuntimeMcpServerConfig(
                "generic-tools", "Generic Tools", null, RuntimeMcpTransport.STDIO,
                "generic-mcp", List.of(), null, Map.of(), Map.of(), Set.of(), Set.of(), Map.of());
        LangChainMcpRuntime runtime = new LangChainMcpRuntime(properties, new ObjectMapper(), server);
        LangChainExecutionContext context = new LangChainExecutionContext(
                "large-output", tempDir, new RuntimeEventListener() {
                    @Override
                    public void onEvent(top.fusb.lingxi.runtime.api.event.RuntimeEvent event) {
                    }
                });
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1")
                .name("arbitrary_tool")
                .arguments("{\"query\":\"account\"}")
                .build();

        LangChainEvidenceStore evidenceStore = new LangChainEvidenceStore(tempDir, new ObjectMapper());
        ToolExecutionResult limited = runtime.limitToolOutput(
                context,
                evidenceStore,
                request,
                ToolExecutionResult.builder().isError(false).resultText("x".repeat(4_000)).build()
        );

        var response = new ObjectMapper().readTree(limited.resultText());
        assertThat(response.path("truncated").asBoolean()).isTrue();
        assertThat(response.path("originalChars").asInt()).isEqualTo(4_000);
        assertThat(response.path("preview").asText()).hasSize(1_000);
        assertThat(response.path("evidenceId").asText()).startsWith("ev-");
        assertThat(response.path("readInstructions").asText()).contains("read_evidence");
        assertThat(response.has("fileRoot")).isFalse();
        assertThat(evidenceStore.readAll(response.path("evidenceId").asText()).content()).hasSize(4_000);
    }

    @Test
    void exposesOnlyFixedGatewaySchemasToTheModel() {
        var specifications = ToolSpecifications.toolSpecificationsFrom(LangChainMcpTools.class);

        assertThat(specifications).extracting(ToolSpecification::name)
                .containsExactlyInAnyOrder("list_mcp_tools", "read_mcp_tool", "invoke_mcp_tool");
        String invokeSchema = specifications.stream()
                .filter(specification -> "invoke_mcp_tool".equals(specification.name()))
                .findFirst()
                .orElseThrow()
                .toJson();
        assertThat(invokeSchema)
                .contains("mcpCode", "toolName", "arguments", "\"type\":\"object\"")
                .doesNotContain("codebase-memory", "Codebase Memory", "list_projects",
                        "get_architecture", "get_code_snippet", "search_code",
                        "index_repository", "search_graph", "query_graph");
    }

    @Test
    void readsAllowlistCatalogWithoutStartingMcpAndRejectsUnauthorizedTools() {
        RuntimeMcpServerConfig restricted = new RuntimeMcpServerConfig(
                "codebase-memory", "Codebase Memory", "Use only indexed projects.", RuntimeMcpTransport.STDIO,
                "missing-mcp-command", List.of(), null, Map.of(), Map.of(), Set.of(),
                Set.of("search_code", "list_projects"), Map.of());
        LangChainMcpRuntime runtime = new LangChainMcpRuntime(
                new LangChainRuntimeProperties(), new ObjectMapper(), restricted);

        assertThat(runtime.toolNames()).containsExactly("list_projects", "search_code");
        assertThatThrownBy(() -> runtime.describeTool("query_graph", true, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未授权工具");
    }

    private RuntimeMcpServerConfig server() {
        return new RuntimeMcpServerConfig(
                "generic-tools", "Generic Tools", null, RuntimeMcpTransport.STDIO,
                "generic-mcp", List.of(), null, Map.of(), Map.of(), Set.of(), Set.of(), Map.of());
    }
}
