package top.fusb.lingxi.runtime.codex.home;

import top.fusb.lingxi.runtime.api.model.RuntimeModelConfig;
import top.fusb.lingxi.runtime.api.model.RuntimeModelProtocol;
import top.fusb.lingxi.runtime.api.execution.RuntimeCommandGuideDescriptor;
import top.fusb.lingxi.runtime.api.execution.RuntimeSkillDescriptor;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpServerConfig;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpTransport;
import top.fusb.lingxi.runtime.codex.config.CodexSkillAccessMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CodexHomeServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void writesConfiguredModelContextWindow() throws Exception {
        CodexHomeService service = new CodexHomeService("C") {
            @Override
            public Path conversationHome(Long conversationRootTaskId) {
                return tempDir.resolve("conversation-" + conversationRootTaskId);
            }
        };
        RuntimeModelConfig modelConfig = new RuntimeModelConfig(
                "test-key", "SYSTEM", "https://example.test/v1", "test-model",
                "high", 200_000, true);

        Path home = service.prepare(17L, 17L, modelConfig);

        assertThat(Files.readString(home.resolve("config.toml")))
                .contains(
                        "model_context_window = 200000",
                        "model_reasoning_effort = \"high\"",
                        "[shell_environment_policy.set]",
                        "LANG = \"C\"",
                        "LC_ALL = \"C\"",
                        "LC_CTYPE = \"C\"");
    }

    @Test
    void writesDeepSeekProProviderAndOfficialModelCatalog() throws Exception {
        CodexHomeService service = new CodexHomeService() {
            @Override
            public Path conversationHome(Long conversationRootTaskId) {
                return tempDir.resolve("conversation-" + conversationRootTaskId);
            }
        };
        RuntimeModelConfig modelConfig = new RuntimeModelConfig(
                "sk-test-key", "SYSTEM", "https://api.deepseek.com/v1", "deepseek-v4-pro",
                "high", 1_048_576, RuntimeModelProtocol.RESPONSES, null,
                false, null, "DEEPSEEK");

        Path home = service.prepare(18L, 18L, modelConfig);
        String escapedModelCatalogPath = home.resolve("models.json").toAbsolutePath().toString()
                .replace("\\", "\\\\");

        assertThat(Files.readString(home.resolve("config.toml")))
                .contains(
                        "model_provider = \"deepseek\"",
                        "preferred_auth_method = \"apikey\"",
                        "forced_login_method = \"api\"",
                        "model_catalog_json = \"" + escapedModelCatalogPath + "\"",
                        "base_url = \"https://api.deepseek.com\"",
                        "wire_api = \"responses\"",
                        "experimental_bearer_token = \"sk-test-key\"")
                .doesNotContain("requires_openai_auth", "model_context_window");
        assertThat(Files.readString(home.resolve("models.json")))
                .contains("\"slug\": \"deepseek-v4-flash\"", "\"slug\": \"deepseek-v4-pro\"",
                        "\"context_window\": 1048576");
        assertThat(home.resolve("auth.json")).doesNotExist();
    }

    @Test
    void rejectsUnsupportedDeepSeekCodexModel() {
        CodexHomeService service = new CodexHomeService() {
            @Override
            public Path conversationHome(Long conversationRootTaskId) {
                return tempDir.resolve("conversation-" + conversationRootTaskId);
            }
        };
        RuntimeModelConfig modelConfig = new RuntimeModelConfig(
                "sk-test-key", "SYSTEM", "https://api.deepseek.com", "deepseek-chat",
                null, 128_000, RuntimeModelProtocol.RESPONSES, null,
                false, null, "DEEPSEEK");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.prepare(19L, 19L, modelConfig))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模型不在模型目录中：deepseek-chat");
    }

    @Test
    void writesBoundStdioAndHttpMcpServers() throws Exception {
        CodexHomeService service = new CodexHomeService() {
            @Override
            public Path conversationHome(Long conversationRootTaskId) {
                return tempDir.resolve("conversation-" + conversationRootTaskId);
            }
        };
        RuntimeModelConfig modelConfig = new RuntimeModelConfig(
                "test-key", "SYSTEM", "https://example.test/v1", "test-model",
                "high", 200_000, true);
        RuntimeMcpServerConfig stdio = new RuntimeMcpServerConfig(
                "local-tools", "Local Tools", "Use local tools.", RuntimeMcpTransport.STDIO, "tool-server",
                List.of("--stdio"), null, Map.of("TOKEN", "secret"), Map.of(), Set.of(),
                Set.of("search_code"), Map.of());
        RuntimeMcpServerConfig http = new RuntimeMcpServerConfig(
                "remote-tools", "Remote Tools", null, RuntimeMcpTransport.STREAMABLE_HTTP, null,
                List.of(), "https://example.test/mcp", Map.of(), Map.of("Authorization", "Bearer token"),
                Set.of(), Set.of(), Map.of());

        Path home = service.prepare(20L, 20L, modelConfig, List.of(stdio, http));

        assertThat(Files.readString(home.resolve("config.toml")))
                .contains(
                        "[mcp_servers.\"local-tools\"]",
                        "command = \"python3\"",
                        "mcp-pagination-proxy.py", "tool-server", "--stdio",
                        "enabled_tools = [\"search_code\"]",
                        "default_tools_approval_mode = \"approve\"",
                        "[mcp_servers.\"local-tools\".env]",
                        "\"TOKEN\" = \"secret\"",
                        "[mcp_servers.\"remote-tools\"]",
                        "url = \"https://example.test/mcp\"",
                        "http_headers = { \"Authorization\" = \"Bearer token\" }");
        assertThat(home.resolve("runtime/mcp-pagination-proxy.py")).isRegularFile();
    }

    @Test
    void installsOnlyModelFacingSkillFiles() throws Exception {
        Path source = tempDir.resolve("skill-source");
        Files.createDirectories(source.resolve("references"));
        Files.createDirectories(source.resolve("scripts"));
        Files.writeString(source.resolve("SKILL.md"), "---\nname: demo\ndescription: Demo.\n---\nUse demo run.\n");
        Files.writeString(source.resolve("references/usage.md"), "Usage");
        Files.writeString(source.resolve("scripts/demo.py"), "print('hidden')");
        Files.writeString(source.resolve("lingxi.json"), "{}");
        CodexHomeService service = new CodexHomeService() {
            @Override
            public Path conversationHome(Long conversationRootTaskId) {
                return tempDir.resolve("conversation-" + conversationRootTaskId);
            }
        };
        RuntimeModelConfig modelConfig = new RuntimeModelConfig(
                "test-key", "SYSTEM", "https://example.test/v1", "test-model",
                "high", 200_000, true);

        Path home = service.prepare(21L, 21L, modelConfig, List.of(),
                List.of(new RuntimeSkillDescriptor("demo", "示例", source.toString())),
                List.of(new RuntimeCommandGuideDescriptor(
                        "resource-memory", "资源记忆", "# 原生模式平台说明")),
                CodexSkillAccessMode.NATIVE_SKILL);

        assertThat(home.resolve("skills/demo/SKILL.md")).isRegularFile();
        assertThat(home.resolve("skills/demo/references/usage.md")).isRegularFile();
        assertThat(home.resolve("skills/demo/scripts")).doesNotExist();
        assertThat(home.resolve("skills/demo/lingxi.json")).doesNotExist();
        assertThat(Files.readString(home.resolve("command-catalog/resource-memory/guide.md"),
                StandardCharsets.UTF_8)).isEqualTo("# 原生模式平台说明");
    }

    @Test
    void projectsCommandGuidesWithoutInstallingNativeSkills() throws Exception {
        Path source = tempDir.resolve("command-source");
        Files.createDirectories(source.resolve("references"));
        Files.createDirectories(source.resolve("scripts"));
        Files.writeString(source.resolve("SKILL.md"), "---\nname: demo\ndescription: Demo.\n---\nUse demo run.\n");
        Files.writeString(source.resolve("references/usage.md"), "Usage");
        Files.writeString(source.resolve("scripts/demo.py"), "print('hidden')");
        CodexHomeService service = new CodexHomeService() {
            @Override
            public Path conversationHome(Long conversationRootTaskId) {
                return tempDir.resolve("conversation-" + conversationRootTaskId);
            }
        };
        RuntimeModelConfig modelConfig = new RuntimeModelConfig(
                "test-key", "SYSTEM", "https://example.test/v1", "test-model",
                "high", 200_000, true);

        Path home = service.prepare(22L, 22L, modelConfig, List.of(),
                List.of(new RuntimeSkillDescriptor("demo", "示例", source.toString())),
                List.of(new RuntimeCommandGuideDescriptor(
                        "resource-memory", "资源记忆", "# 资源记忆\n\nUse resource-memory search.")),
                CodexSkillAccessMode.COMMAND_CATALOG);

        assertThat(home.resolve("command-catalog/demo/guide.md")).isRegularFile();
        assertThat(home.resolve("command-catalog/demo/references/usage.md")).isRegularFile();
        assertThat(home.resolve("command-catalog/demo/scripts")).doesNotExist();
        assertThat(Files.readString(home.resolve("command-catalog/resource-memory/guide.md"),
                StandardCharsets.UTF_8)).isEqualTo("# 资源记忆\n\nUse resource-memory search.");
        assertThat(home.resolve("skills")).doesNotExist();
    }
}
