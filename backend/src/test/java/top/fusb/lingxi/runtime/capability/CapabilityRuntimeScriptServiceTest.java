package top.fusb.lingxi.runtime.capability;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.config.LingxiProperties;
import top.fusb.lingxi.prompt.PromptTemplateService;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionEnvironment;
import top.fusb.lingxi.definition.CapabilityCommandExtensionDefinition;
import top.fusb.lingxi.definition.CapabilityRuntimeModuleDefinition;
import top.fusb.lingxi.dto.CapabilityCommandOutputDefinition;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionEnvironment;
import top.fusb.lingxi.runtime.api.execution.RuntimeWorkspaceLayout;
import top.fusb.lingxi.runtime.api.event.RuntimeActionIcon;
import top.fusb.lingxi.service.CapabilityConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityRuntimeScriptServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void usesConfiguredSharedPackageCacheAndSeparatesPythonRuntimeArtifacts() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path packageCache = tempDir.resolve("shared-python-packages");
        LingxiProperties properties = new LingxiProperties();
        properties.getTask().setContentDir(tempDir.resolve("tasks").toString());
        properties.getCapabilityRuntime().setPackageCacheDir(packageCache.toString());
        CapabilityRuntimeDefinitionService definitionService = mock(CapabilityRuntimeDefinitionService.class);
        AgentRuntimeAccessService accessService = mock(AgentRuntimeAccessService.class);
        when(definitionService.modules()).thenReturn(List.of());
        when(accessService.issue(anyLong(), any(), any())).thenReturn("runtime-token");
        ObjectMapper objectMapper = new ObjectMapper();
        CapabilityRuntimeScriptService service = new CapabilityRuntimeScriptService(
                properties, definitionService, accessService, mock(CapabilityConfigService.class), objectMapper,
                new PromptTemplateService());

        RuntimeExecutionEnvironment environment = service.ensureScript(
                278L, layout(workspace), "tester", Set.of("project-hub"), Map.of());

        Path runtimeRoot = tempDir.resolve("tasks/runtime/task-278");
        Map<String, Object> registry = objectMapper.readValue(
                Files.readString(runtimeRoot.resolve("registry.json")), new TypeReference<>() {});
        String launcher = Files.readString(runtimeRoot.resolve("capability"));
        assertThat(registry.get("packageCacheRoot")).isEqualTo(packageCache.toAbsolutePath().normalize().toString());
        assertThat(launcher).contains("sys.implementation, \"cache_tag\"");
        assertThat(launcher).contains("sys.platform, platform.machine()");
        assertThat(runtimeRoot.resolve("python-packages")).doesNotExist();
        Map<?, ?> commands = (Map<?, ?>) registry.get("commands");
        assertThat(commands.containsKey("resource-memory.providers")).isFalse();
        Map<?, ?> resourceSearch = (Map<?, ?>) commands.get("resource-memory.search");
        assertThat(resourceSearch.get("executable")).isEqualTo("bin/resource-memory");
        assertThat(resourceSearch.get("prefixArguments")).isEqualTo(List.of("search"));
        assertThat(environment.commandGuides()).singleElement().satisfies(guide -> {
            assertThat(guide.code()).isEqualTo("resource-memory");
            assertThat(guide.content()).contains("project-hub", "--include-related", "sourceConfigId");
        });
        assertThat(environment.commands().stream()
                .filter(command -> "resource-memory.search".equals(command.code()))
                .findFirst().orElseThrow().parameters())
                .extracting("name")
                .containsExactly("query", "includeRelated");
        assertThat(environment.commands().stream()
                .filter(command -> "resource-memory.save".equals(command.code()))
                .findFirst().orElseThrow().description())
                .contains("project-hub", "providerCode", "sourceConfigId", "entries", "stable-ref")
                .doesNotContain("平台命令说明");
        assertThat(environment.commands().stream()
                .filter(command -> "resource-memory.invalidate".equals(command.code()))
                .findFirst().orElseThrow().description())
                .contains("project-hub", "providerCode", "sourceConfigId", "refs", "stable-ref")
                .doesNotContain("平台命令说明");
    }

    @Test
    void installsSkillAndRunsPublicBusinessCommand() throws Exception {
        Path workspace = tempDir.resolve("filtered-workspace");
        Files.createDirectories(workspace);
        Path skillDirectory = tempDir.resolve("sample");
        Path scriptsDirectory = skillDirectory.resolve("scripts");
        Files.createDirectories(scriptsDirectory);
        Files.writeString(skillDirectory.resolve("SKILL.md"), """
                ---
                name: sample
                description: Sample test Skill.
                ---
                """);
        Files.writeString(scriptsDirectory.resolve("sample_tool.py"), """
                import sys
                print("|".join(sys.argv[1:]))
                """);

        CapabilityRuntimeModuleDefinition module = new CapabilityRuntimeModuleDefinition();
        module.setModuleCode("sample");
        module.setDisplayName("示例能力");
        module.setEntrypoint("scripts/sample_tool.py");
        module.setModuleDirectory(skillDirectory);
        CapabilityCommandExtensionDefinition commandDefinition = new CapabilityCommandExtensionDefinition();
        commandDefinition.setCommand("sample run");
        commandDefinition.setDisplayName("运行示例");
        commandDefinition.setIcon(RuntimeActionIcon.PLAY_CIRCLE);
        CapabilityCommandOutputDefinition boundOutput = new CapabilityCommandOutputDefinition();
        boundOutput.setType("file-root");
        boundOutput.setPathField("worktreePath");
        boundOutput.setFeatures(List.of("file-access"));
        commandDefinition.setOutputs(List.of(boundOutput));
        module.setCommands(List.of(commandDefinition));
        LingxiProperties properties = new LingxiProperties();
        properties.getTask().setContentDir(tempDir.resolve("tasks").toString());
        properties.getCapabilityRuntime().setPackageCacheDir(tempDir.resolve("cache").toString());
        CapabilityRuntimeDefinitionService definitionService = mock(CapabilityRuntimeDefinitionService.class);
        AgentRuntimeAccessService accessService = mock(AgentRuntimeAccessService.class);
        when(definitionService.modules()).thenReturn(List.of(module));
        when(accessService.issue(anyLong(), any(), any())).thenReturn("runtime-token");
        ObjectMapper objectMapper = new ObjectMapper();
        CapabilityRuntimeScriptService service = new CapabilityRuntimeScriptService(
                properties, definitionService, accessService, mock(CapabilityConfigService.class), objectMapper,
                new PromptTemplateService());

        RuntimeExecutionEnvironment environment = service.ensureScript(
                344L, layout(workspace), "tester", Set.of("sample"),
                Map.of("sample", Set.of("sample.run")));

        Path runtimeRoot = tempDir.resolve("tasks/runtime/task-344");
        Map<String, Object> registry = objectMapper.readValue(
                Files.readString(runtimeRoot.resolve("registry.json")), new TypeReference<>() {});
        Map<?, ?> skills = (Map<?, ?>) registry.get("skills");
        Map<?, ?> sample = (Map<?, ?>) skills.get("sample");
        Map<?, ?> commands = (Map<?, ?>) registry.get("commands");
        assertThat(sample.get("directory")).isEqualTo("skills/sample");
        assertThat(commands.containsKey("sample.read")).isFalse();
        assertThat(workspace.resolve(".agent-task/skills")).doesNotExist();
        assertThat(workspace.resolve(".agent-capabilities")).doesNotExist();
        assertThat(workspace.resolve(".agent-skills")).doesNotExist();
        assertThat(runtimeRoot.resolve("skills/sample/scripts/sample_tool.py")).isRegularFile();
        assertThat(environment.skills()).singleElement().satisfies(skill ->
                assertThat(skill.sourcePath()).isEqualTo(skillDirectory.toAbsolutePath().normalize().toString()));
        assertThat(environment.commands().stream().filter(command -> command.skillCommand()).toList())
                .singleElement().satisfies(command -> {
            assertThat(command.moduleCode()).isEqualTo("sample");
            assertThat(command.code()).isEqualTo("sample.run");
            assertThat(command.command()).isEqualTo("sample run");
            assertThat(command.name()).isEqualTo("运行示例");
            assertThat(command.icon()).isEqualTo(RuntimeActionIcon.PLAY_CIRCLE);
            assertThat(command.outputFeatures()).containsExactly("file-access");
        });

        Process process = new ProcessBuilder(
                runtimeRoot.resolve("bin/sample").toString(),
                "run", "--value", "x")
                .directory(workspace.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).isZero();
        assertThat(output).isEqualTo("sample.run|--value|x\n");
    }

    private RuntimeWorkspaceLayout layout(Path workspace) throws Exception {
        Path executionRoot = workspace.toAbsolutePath().normalize();
        Path taskContextRoot = executionRoot.resolve("backend-context");
        Path runtimeInputRoot = taskContextRoot.resolve("inputs");
        Path artifactsRoot = taskContextRoot.resolve("artifacts");
        Path runtimeRoot = taskContextRoot.resolve("runtime-data");
        Path runtimeStateRoot = runtimeRoot.resolve("langchain-state");
        Path privateRuntimeRoot = runtimeRoot.resolve("private");
        Files.createDirectories(runtimeInputRoot);
        Files.createDirectories(artifactsRoot);
        Files.createDirectories(runtimeStateRoot);
        Files.createDirectories(privateRuntimeRoot);
        return new RuntimeWorkspaceLayout(
                executionRoot.toString(), taskContextRoot.toString(), runtimeInputRoot.toString(),
                artifactsRoot.toString(), runtimeRoot.toString(), runtimeStateRoot.toString(),
                privateRuntimeRoot.toString());
    }
}
