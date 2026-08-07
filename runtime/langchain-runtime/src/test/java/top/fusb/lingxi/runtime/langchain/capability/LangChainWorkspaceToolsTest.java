package top.fusb.lingxi.runtime.langchain.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.runtime.api.event.RuntimeEventListener;
import top.fusb.lingxi.runtime.api.execution.RuntimeCommandDescriptor;
import top.fusb.lingxi.runtime.api.execution.RuntimeCommandGuideDescriptor;
import top.fusb.lingxi.runtime.api.execution.RuntimeCommandOutputDescriptor;
import top.fusb.lingxi.runtime.api.execution.RuntimeCommandParameterDescriptor;
import top.fusb.lingxi.runtime.api.execution.RuntimeCommandParameterType;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionEnvironment;
import top.fusb.lingxi.runtime.api.execution.RuntimeSkillDescriptor;
import top.fusb.lingxi.runtime.api.execution.RuntimeWorkspaceLayout;
import top.fusb.lingxi.runtime.langchain.agent.LangChainExecutionContext;
import top.fusb.lingxi.runtime.langchain.agent.evidence.LangChainEvidenceStore;
import top.fusb.lingxi.runtime.langchain.config.LangChainRuntimeProperties;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangChainWorkspaceToolsTest {

    @TempDir
    Path workspace;

    private RuntimeExecutionEnvironment environment = RuntimeExecutionEnvironment.empty();

    @Test
    void executesOnlyInstalledCapabilityCommand() throws Exception {
        Path launcher = installExecutable("capability", """
                #!/bin/sh
                printf '%s|' "$@"
                """);
        environment = environment(List.of(nativeCommand(
                "demo.run", "demo run", "执行样例", launcher, List.of("demo", "run"))), List.of());

        LangChainWorkspaceTools tools = tools();

        assertThat(tools.runCapabilityCommand("demo.run", List.of("--value", "hello world")))
                .isEqualTo("demo|run|--value|hello world|");
        assertThat(tools.runCapabilityCommand("demo run", List.of("--value", "from cli identity")))
                .isEqualTo("demo|run|--value|from cli identity|");
        assertThatThrownBy(() -> tools.runCapabilityCommand("demo.delete", List.of()))
                .hasMessageContaining("平台命令未安装");
    }

    @Test
    void rejectsMissingPrivateExecutable() {
        environment = environment(List.of(new RuntimeCommandDescriptor(
                null, "demo.run", "demo run", "执行样例", null,
                workspace.resolve("missing-capability").toString(), List.of("demo", "run"), List.of())), List.of());

        assertThatThrownBy(() -> tools().runCapabilityCommand("demo.run", List.of()))
                .hasMessageContaining("missing-capability");
    }

    @Test
    void restrictsWorkspaceFileWrites() throws Exception {
        LangChainWorkspaceTools tools = tools();

        String fileNamePath = tools.writeWorkspaceFile("repository.json", "{\"name\":\"demo\"}");
        String nestedPath = tools.writeWorkspaceFile("queries/query.sql", "SELECT 1");

        assertThat(fileNamePath).isEqualTo("backend-context/inputs/repository.json");
        assertThat(nestedPath).isEqualTo("backend-context/inputs/queries/query.sql");
        assertThat(Files.readString(workspace.resolve(fileNamePath))).isEqualTo("{\"name\":\"demo\"}");
        assertThat(Files.readString(workspace.resolve(nestedPath))).isEqualTo("SELECT 1");
        assertThatThrownBy(() -> tools.writeWorkspaceFile("../outside.json", "value"))
                .hasMessageContaining("只能写入");
        assertThatThrownBy(() -> tools.writeWorkspaceFile(workspace.resolve("absolute.json").toString(), "value"))
                .hasMessageContaining("只能写入");
    }

    @Test
    void rejectsWorkspaceSymlinksForWrites() throws Exception {
        Path outsideDirectory = workspace.resolve("outside");
        Files.createDirectories(outsideDirectory);
        Files.createDirectories(runtimeInputRoot());
        Files.createSymbolicLink(runtimeInputRoot().resolve("escaped"), outsideDirectory);
        LangChainWorkspaceTools tools = tools();

        assertThatThrownBy(() -> tools.writeWorkspaceFile(
                "escaped/new.txt", "value"))
                .hasMessageContaining("符号链接");
    }

    @Test
    void noLongerExposesTypeSpecificReadTools() {
        var specifications = ToolSpecifications.toolSpecificationsFrom(LangChainWorkspaceTools.class);
        String schemas = specifications.toString();

        assertThat(schemas).doesNotContain(
                "run_capability_command", "run_skill_command", "read_workspace_file",
                "read_workspace_files", "view_workspace_images", "read_command_guide");
        assertThat(schemas).contains("read_skill_file");
    }

    @Test
    void readsOnlyAuthorizedSkillInstructionsAndRunsPublicCommand() throws Exception {
        Path skillRoot = workspace.resolve("installed-skills/demo");
        Files.createDirectories(skillRoot.resolve("references"));
        Files.createDirectories(skillRoot.resolve("scripts"));
        Files.writeString(skillRoot.resolve("SKILL.md"), "# Demo Skill\n", StandardCharsets.UTF_8);
        Files.writeString(skillRoot.resolve("references/scope.md"), "# Boundary\n", StandardCharsets.UTF_8);
        Files.writeString(skillRoot.resolve("scripts/demo.py"), "print('demo')\n", StandardCharsets.UTF_8);
        Files.writeString(skillRoot.resolve("lingxi.json"), "{}", StandardCharsets.UTF_8);
        Path launcher = installExecutable("demo", "#!/bin/sh\nprintf '%s|' \"$@\"");
        environment = environment(List.of(skillCommand(
                        "demo", "demo.run", "demo run", "运行示例", launcher, List.of("run"), List.of())),
                List.of(new RuntimeSkillDescriptor("demo", "示例", skillRoot.toString())));
        ObjectMapper objectMapper = new ObjectMapper();
        LangChainCapabilityRegistry registry = new LangChainCapabilityRegistry(environment);
        LangChainWorkspaceTools tools = tools(new LangChainRuntimeProperties(), registry);

        assertThat(registry.skillDisplayName("demo")).isEqualTo("示例");
        assertThat(tools.readSkillFile("demo", "SKILL.md", null, null)).contains("# Demo Skill");
        assertThat(tools.readSkillFile("demo", "references/scope.md", null, null)).contains("# Boundary");
        assertThatThrownBy(() -> tools.readSkillFile("demo", "scripts/demo.py", null, null))
                .hasMessageContaining("只能读取 Skill 的 SKILL.md 或 references 文件");
        assertThatThrownBy(() -> tools.readSkillFile("demo", "lingxi.json", null, null))
                .hasMessageContaining("只能读取 Skill 的 SKILL.md 或 references 文件");
        assertThat(tools.runSkillCommand("demo run", List.of("--value", "x")))
                .isEqualTo("run|--value|x|");
        assertThat(workspace.resolve(".agent-capabilities")).doesNotExist();
        assertThat(workspace.resolve(".agent-skills")).doesNotExist();
    }

    @Test
    void registersOnlyAuthorizedSkillCommandsInDynamicEnumSchema() throws Exception {
        Path skillRoot = workspace.resolve("installed-skills/project-hub");
        Path unreadSkillRoot = workspace.resolve("installed-skills/code-repository");
        Files.createDirectories(skillRoot);
        Files.createDirectories(unreadSkillRoot);
        Files.writeString(skillRoot.resolve("SKILL.md"), "# Project Hub\n", StandardCharsets.UTF_8);
        Files.writeString(unreadSkillRoot.resolve("SKILL.md"), "# Code Repository\n", StandardCharsets.UTF_8);
        Path launcher = workspace.resolve("unused-project-hub-executable");
        environment = environment(List.of(
                        skillCommand("project-hub", "project-hub.list", "project-hub project-list",
                                "项目列表", launcher, List.of("project-list"), List.of()),
                        skillCommand("project-hub", "project-hub.context", "project-hub project-context",
                                "项目上下文", launcher, List.of("project-context"), List.of()),
                        skillCommand("code-repository", "code-repository.info", "code-repository repository-info",
                                "仓库信息", launcher, List.of("repository-info"), List.of()),
                        skillCommand("unmounted", "private.hidden", "private hidden",
                                "未挂载命令", launcher, List.of("hidden"), List.of())),
                List.of(
                        new RuntimeSkillDescriptor("project-hub", "项目中心", skillRoot.toString()),
                        new RuntimeSkillDescriptor("code-repository", "代码仓库", unreadSkillRoot.toString())));
        ObjectMapper objectMapper = new ObjectMapper();
        LangChainCapabilityRegistry registry = new LangChainCapabilityRegistry(environment);
        LangChainRegistryToolProvider provider = new LangChainRegistryToolProvider(
                registry, null, objectMapper);

        assertThat(provider.isDynamic()).isTrue();
        assertThat(provider.provideTools(null).tools()).isEmpty();

        registry.recordSkillInstructionsRead("project-hub", 1, 1, null);
        assertThat(provider.provideTools(null).tools()).isEmpty();
        registry.recordSkillInstructionsRead("project-hub", 2, 2, 2);
        var provided = provider.provideTools(null);
        var specification = provided.toolSpecificationByName("run_skill_command");
        JsonEnumSchema commandSchema = (JsonEnumSchema) specification.parameters()
                .properties().get("command");

        assertThat(registry.skillCommands()).extracting(LangChainCapabilityRegistry.SkillCommand::command)
                .containsExactly("code-repository repository-info",
                        "project-hub project-context", "project-hub project-list");
        assertThat(provided.tools()).hasSize(1);
        assertThat(specification.strict()).isNull();
        assertThat(specification.parameters().required()).containsExactly("command", "arguments");
        assertThat(specification.parameters().additionalProperties()).isFalse();
        assertThat(commandSchema.enumValues())
                .containsExactly("project-hub project-context", "project-hub project-list");
        assertThat(specification.name()).isEqualTo("run_skill_command");
        assertThatThrownBy(() -> provided.toolExecutorByName(specification.name()).execute(
                ToolExecutionRequest.builder()
                        .id("fabricated-skill-call")
                        .name(specification.name())
                        .arguments("{\"command\":\"project-hub project-detail\",\"arguments\":[]}")
                        .build(),
                "test"
        )).hasMessageContaining("当前任务未声明", "当前可用命令", "project-hub project-context");
    }

    @Test
    void doesNotExposePlatformCommandGuidesToLangChain() {
        environment = new RuntimeExecutionEnvironment(
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new RuntimeCommandGuideDescriptor(
                        "resource-memory", "资源记忆", "# 资源记忆\n\n保存格式说明")));

        ObjectMapper objectMapper = new ObjectMapper();
        LangChainCapabilityRegistry registry = new LangChainCapabilityRegistry(environment);
        LangChainRegistryToolProvider provider = new LangChainRegistryToolProvider(
                registry, null, objectMapper);
        var provided = provider.provideTools(null);

        assertThat(provided.tools()).isEmpty();
    }

    @Test
    void registersPlatformCommandAsNativeTool() throws Exception {
        Path launcher = installExecutable("capability", "#!/bin/sh\nprintf '%s|' \"$@\"");
        environment = environment(List.of(new RuntimeCommandDescriptor(
                null, "demo.run", "demo run", "执行样例", "读取样例数据",
                launcher.toString(), List.of("demo", "run"), List.of())), List.of());
        ObjectMapper objectMapper = new ObjectMapper();
        LangChainCapabilityRegistry registry = new LangChainCapabilityRegistry(environment);
        LangChainWorkspaceTools workspaceTools = tools(new LangChainRuntimeProperties(), registry);
        LangChainRegistryToolProvider provider = new LangChainRegistryToolProvider(
                registry, workspaceTools, objectMapper);

        var provided = provider.provideTools(null);
        String toolName = registry.nativeCommands().get(0).toolName();

        assertThat(toolName).isEqualTo("demo__run");
        assertThat(registry.commandForNativeTool(toolName).capabilityCommand()).isFalse();
        assertThat(provided.tools()).hasSize(1);
        assertThat(provided.toolSpecificationByName(toolName).description())
                .contains("执行样例", "读取样例数据", "demo run");
        assertThat(provided.toolSpecificationByName(toolName).parameters().properties())
                .containsOnlyKeys("arguments");
        assertThat(provided.toolExecutorByName(toolName).execute(
                ToolExecutionRequest.builder()
                        .id("call-1")
                        .name(toolName)
                        .arguments("{\"arguments\":[\"--value\",\"hello\"]}")
                        .build(),
                "test"
        )).isEqualTo("demo|run|--value|hello|");
        assertThat(provided.toolExecutorByName(toolName).execute(
                ToolExecutionRequest.builder()
                        .id("call-2")
                        .name(toolName)
                        .arguments("{}")
                        .build(),
                "test"
        )).isEqualTo("demo|run|");
        assertThat(provided.tools().keySet().stream().map(item -> item.name()).toList())
                .containsExactly("demo__run");
    }

    @Test
    void executesPlatformCommandFromRegistryContract() throws Exception {
        Path executable = installExecutable("resource-memory", """
                #!/bin/sh
                printf '%s|' "$@"
                """);
        environment = environment(List.of(nativeCommand(
                "resource-memory.search", "resource-memory search", "检索资源记忆",
                executable, List.of("search"))), List.of());

        String output = tools().runCapabilityCommand("resource-memory search", List.of("--query", "demo"));

        assertThat(output).isEqualTo("search|--query|demo|");
        LangChainCapabilityRegistry registry = new LangChainCapabilityRegistry(environment);
        String toolName = registry.nativeCommands().get(0).toolName();
        assertThat(toolName).isEqualTo("resource-memory__search");
        assertThat(registry.commandForNativeTool(toolName).capabilityCommand()).isFalse();
    }

    @Test
    void exposesStructuredPlatformCommandParametersAndConvertsThemToCliArguments() throws Exception {
        Path executable = installExecutable("resource-memory", "#!/bin/sh\nprintf '%s|' \"$@\"");
        RuntimeCommandDescriptor command = new RuntimeCommandDescriptor(
                null, "resource-memory.search", "resource-memory search", "检索资源记忆",
                "检索可复用资源", executable.toString(), List.of("search"), List.of(), "resource-memory",
                List.of(
                        new RuntimeCommandParameterDescriptor("query", "--query",
                                RuntimeCommandParameterType.STRING, true, "资源检索描述"),
                        new RuntimeCommandParameterDescriptor("includeRelated", "--include-related",
                                RuntimeCommandParameterType.BOOLEAN, false, "是否包含主题相关线索")));
        environment = environment(List.of(command), List.of());
        ObjectMapper objectMapper = new ObjectMapper();
        LangChainCapabilityRegistry registry = new LangChainCapabilityRegistry(environment);
        LangChainRegistryToolProvider provider = new LangChainRegistryToolProvider(
                registry, tools(new LangChainRuntimeProperties(), registry), objectMapper);
        String toolName = registry.nativeCommands().get(0).toolName();
        var specification = provider.provideTools(null).toolSpecificationByName(toolName);

        assertThat(specification.parameters().properties()).containsOnlyKeys("query", "includeRelated");
        assertThat(specification.parameters().required()).containsExactly("query");
        assertThat(provider.provideTools(null).toolExecutorByName(toolName).execute(
                ToolExecutionRequest.builder()
                        .id("call-structured")
                        .name(toolName)
                        .arguments("{\"query\":\"示例服务\",\"includeRelated\":true}")
                        .build(),
                "test"
        )).isEqualTo("search|--query|示例服务|--include-related|");
    }

    @Test
    void doesNotParseCommandOutputWhenResourceOutputsAreEmpty() throws Exception {
        installCapability("""
                {"commands":{"demo.run":{"code":"DEMO_RUN","executable":"capability","prefixArguments":["demo","run"],"outputs":[]}}}
                """, """
                #!/bin/sh
                printf 'plain output'
                """);

        assertThat(tools().runCapabilityCommand("demo.run", List.of())).isEqualTo("plain output");
    }

    @Test
    void externalizesOversizedCapabilityOutputWithoutBreakingJson() throws Exception {
        installCapability("""
                {"commands":{"demo.run":{"code":"DEMO_RUN","executable":"capability","prefixArguments":["demo","run"],"outputs":[]}}}
                """, """
                #!/bin/sh
                printf '{"items":["'
                i=0
                while [ "$i" -lt 13000 ]; do printf 'x'; i=$((i + 1)); done
                printf '"]}'
                """);

        JsonNode result = new ObjectMapper().readTree(tools().runCapabilityCommand("demo.run", List.of()));

        assertThat(result.path("truncated").asBoolean()).isTrue();
        assertThat(result.path("originalChars").asInt()).isGreaterThan(12_000);
        assertThat(result.path("fields").toString()).contains("items");
        assertThat(result.path("collectionSizes").path("items").asInt()).isEqualTo(1);
        assertThat(result.path("evidenceId").asText()).startsWith("ev-");
        assertThat(result.path("readInstructions").asText()).contains("read_evidence");
        assertThat(result.has("fileRoot")).isFalse();
        assertThat(new LangChainEvidenceStore(evidenceRoot(), new ObjectMapper())
                .readAll(result.path("evidenceId").asText()).content()).contains("\"items\"");
    }

    @Test
    void deduplicatesLargeContentOnlyAfterTheCapabilityRunsAgain() throws Exception {
        installCapability("""
                {"commands":{"demo.run":{"code":"DEMO_RUN","executable":"capability","prefixArguments":["demo","run"],"outputs":[]}}}
                """, """
                #!/bin/sh
                count_file='capability-count'
                count=0
                if [ -f "$count_file" ]; then count=$(cat "$count_file"); fi
                count=$((count + 1))
                printf '%s' "$count" > "$count_file"
                i=0
                while [ "$i" -lt 13000 ]; do printf 'x'; i=$((i + 1)); done
                """);
        LangChainWorkspaceTools tools = tools();

        JsonNode first = new ObjectMapper().readTree(tools.runCapabilityCommand("demo.run", List.of()));
        JsonNode second = new ObjectMapper().readTree(tools.runCapabilityCommand("demo.run", List.of()));

        assertThat(first.path("deduplicated").asBoolean()).isFalse();
        assertThat(second.path("deduplicated").asBoolean()).isTrue();
        assertThat(second.path("executed").asBoolean()).isTrue();
        assertThat(Files.readString(workspace.resolve("capability-count"))).isEqualTo("2");
        assertThat(second.path("evidenceId").asText()).isEqualTo(first.path("evidenceId").asText());
    }

    @Test
    void adaptsDeclaredCapabilityOutputToRuntimeResource() throws Exception {
        Path skillRoot = workspace.resolve("installed-skills/demo");
        Files.createDirectories(skillRoot.resolve("scripts"));
        Files.writeString(skillRoot.resolve("SKILL.md"), "# Demo\n", StandardCharsets.UTF_8);
        Path launcher = installExecutable("demo", """
                #!/bin/sh
                printf '{"worktreePath":"/tmp/demo-worktree","branch":"main"}'
                """);
        RuntimeCommandOutputDescriptor output = new RuntimeCommandOutputDescriptor(
                "file-root", "worktreePath", Set.of("file-access"));
        environment = environment(List.of(skillCommand(
                        "demo", "demo.run", "demo run", "运行示例", launcher,
                        List.of("run"), List.of(output))),
                List.of(new RuntimeSkillDescriptor("demo", "示例", skillRoot.toString())));
        ObjectMapper objectMapper = new ObjectMapper();
        LangChainCapabilityRegistry registry = new LangChainCapabilityRegistry(environment);
        LangChainWorkspaceTools tools = tools(new LangChainRuntimeProperties(), registry);
        registry.recordSkillInstructionsRead("demo", 1, 1, 1);

        String result = tools.runSkillCommand("demo run", List.of());

        assertThat(new ObjectMapper().readTree(result).path("branch").asText()).isEqualTo("main");
        assertThat(new ObjectMapper().readTree(result).path("runtimeResources").get(0))
                .isEqualTo(new ObjectMapper().readTree(
                        "{\"type\":\"file-root\",\"location\":\"/tmp/demo-worktree\",\"features\":[\"file-access\"]}"));
    }

    @Test
    void keepsDisplayNameOnTheExecutableCommandDescriptor() throws Exception {
        Path launcher = installExecutable("resource-memory", "#!/bin/sh\nprintf 'ok'");
        environment = environment(List.of(nativeCommand(
                "resource-memory.search", "resource-memory search", "检索资源记忆",
                launcher, List.of("search"))), List.of());

        LangChainCapabilityRegistry.NativeCommand command =
                new LangChainCapabilityRegistry(environment).nativeCommands().get(0);

        assertThat(command.commandKey()).isEqualTo("resource-memory.search");
        assertThat(command.name()).isEqualTo("检索资源记忆");
    }

    @Test
    void resolvesPublicCommandIndependentlyFromSkillNameAndHonorsAuthorization() throws Exception {
        Path demoRoot = workspace.resolve("installed-skills/demo");
        Path otherRoot = workspace.resolve("installed-skills/other");
        Files.createDirectories(demoRoot);
        Files.createDirectories(otherRoot);
        Files.writeString(demoRoot.resolve("SKILL.md"), "# Demo\n");
        Files.writeString(otherRoot.resolve("SKILL.md"), "# Other\n");
        Path launcher = installExecutable("public-cli", "#!/bin/sh\nprintf 'ok'");
        environment = environment(List.of(skillCommand(
                        "demo", "public-cli.run", "public-cli run", "运行示例",
                        launcher, List.of("run"), List.of())),
                List.of(
                        new RuntimeSkillDescriptor("demo", "示例", demoRoot.toString()),
                        new RuntimeSkillDescriptor("other", "其他", otherRoot.toString())));
        LangChainCapabilityRegistry registry = new LangChainCapabilityRegistry(environment);

        LangChainCapabilityRegistry.SkillCommand command = registry.resolveSkillCommand("public-cli run");
        assertThat(command.skillName()).isEqualTo("demo");
        assertThat(command.descriptor().executable()).isEqualTo(launcher.toString());
        assertThat(command.descriptor().prefixArguments()).containsExactly("run");
        assertThatThrownBy(() -> registry.resolveSkillCommand("other-cli run"))
                .hasMessageContaining("当前任务未声明");
        assertThatThrownBy(() -> registry.resolveSkillCommand("public-cli detail"))
                .hasMessageContaining("当前任务未声明", "当前可用命令", "public-cli run");
    }

    @Test
    void externalizesFailedOutputPrivatelyAndReturnsOnlyARedactedSummary() throws Exception {
        installCapability("""
                {"commands":{"demo.run":{"code":"DEMO_RUN","executable":"capability","prefixArguments":["demo","run"],"outputs":[]}}}
                """, """
                #!/bin/sh
                printf 'password=plain-secret Authorization: Bearer private-token sk-abcdefgh12345678'
                exit 2
                """);
        LangChainWorkspaceTools tools = tools();

        assertThatThrownBy(() -> tools.runCapabilityCommand("demo.run", List.of()))
                .hasMessageContaining("\"exitCode\":2")
                .hasMessageContaining("\"privateEvidence\":true")
                .hasMessageContaining("sk-****")
                .hasMessageNotContaining("plain-secret")
                .hasMessageNotContaining("private-token")
                .satisfies(error -> {
                    try {
                        Path privateDirectory = privateRuntimeRoot().resolve("capability-failures");
                        Path evidence;
                        try (var files = Files.list(privateDirectory)) {
                            evidence = files.findFirst().orElseThrow();
                        }
                        assertThat(Files.readString(evidence)).contains("plain-secret", "private-token");
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                });
    }

    private void installCapability(String registry, String launcherScript) throws Exception {
        Path launcher = installExecutable("capability", launcherScript);
        JsonNode definitions = new ObjectMapper().readTree(registry).path("commands");
        List<RuntimeCommandDescriptor> commands = new java.util.ArrayList<>();
        definitions.fields().forEachRemaining(entry -> {
            JsonNode definition = entry.getValue();
            List<String> prefix = new java.util.ArrayList<>();
            definition.path("prefixArguments").forEach(value -> prefix.add(value.asText()));
            String publicCommand = entry.getKey().replace('.', ' ');
            commands.add(new RuntimeCommandDescriptor(
                    null, entry.getKey(), publicCommand,
                    definition.path("name").asText(entry.getKey()),
                    definition.path("description").asText(null),
                    launcher.toString(), prefix, List.of()));
        });
        environment = environment(commands, List.of());
    }

    private Path installExecutable(String name, String script) throws Exception {
        Path executable = workspace.resolve("private-runtime/bin").resolve(name);
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, script, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(executable, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        ));
        return executable.toAbsolutePath().normalize();
    }

    private RuntimeCommandDescriptor nativeCommand(String code, String command, String name,
                                                     Path executable, List<String> prefixArguments) {
        return new RuntimeCommandDescriptor(null, code, command, name, null,
                executable.toString(), prefixArguments, List.of());
    }

    private RuntimeCommandDescriptor skillCommand(String moduleCode, String code, String command, String name,
                                                    Path executable, List<String> prefixArguments,
                                                    List<RuntimeCommandOutputDescriptor> outputs) {
        return new RuntimeCommandDescriptor(moduleCode, code, command, name, null,
                executable.toString(), prefixArguments, outputs);
    }

    private RuntimeExecutionEnvironment environment(List<RuntimeCommandDescriptor> commands,
                                                    List<RuntimeSkillDescriptor> skills) {
        return new RuntimeExecutionEnvironment(Map.of(), List.of(), List.of(), List.of(), commands, skills);
    }

    private LangChainWorkspaceTools tools() {
        LangChainRuntimeProperties properties = new LangChainRuntimeProperties();
        properties.setCapabilityCommandTimeout(Duration.ofSeconds(5));
        return tools(properties);
    }

    private LangChainWorkspaceTools tools(LangChainRuntimeProperties properties) {
        properties.setCapabilityCommandTimeout(Duration.ofSeconds(5));
        ObjectMapper objectMapper = new ObjectMapper();
        LangChainCapabilityRegistry registry = new LangChainCapabilityRegistry(environment);
        return tools(properties, registry);
    }

    private LangChainWorkspaceTools tools(LangChainRuntimeProperties properties,
                                          LangChainCapabilityRegistry registry) {
        properties.setCapabilityCommandTimeout(Duration.ofSeconds(5));
        LangChainExecutionContext context = new LangChainExecutionContext(
                "test", layout(), new RuntimeEventListener() {
                    @Override
                    public void onEvent(top.fusb.lingxi.runtime.api.event.RuntimeEvent event) {
                    }
                }, RuntimeExecutionEnvironment.empty(), null,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE, 0L);
        ObjectMapper objectMapper = new ObjectMapper();
        return new LangChainWorkspaceTools(properties, context, objectMapper, registry,
                new LangChainEvidenceStore(evidenceRoot(), objectMapper),
                output -> objectMapper.valueToTree(Map.of(
                        "type", output.type(),
                        "location", output.location(),
                        "features", output.features()
                )));
    }

    private RuntimeWorkspaceLayout layout() {
        try {
            Path taskContextRoot = workspace.resolve("backend-context");
            Path runtimeRoot = taskContextRoot.resolve("runtime-data");
            Files.createDirectories(runtimeInputRoot());
            Files.createDirectories(taskContextRoot.resolve("artifacts"));
            Files.createDirectories(runtimeRoot.resolve("langchain-state"));
            Files.createDirectories(privateRuntimeRoot());
            return new RuntimeWorkspaceLayout(
                    workspace.toAbsolutePath().normalize().toString(), taskContextRoot.toString(),
                    runtimeInputRoot().toString(), taskContextRoot.resolve("artifacts").toString(),
                    runtimeRoot.toString(), runtimeRoot.resolve("langchain-state").toString(),
                    privateRuntimeRoot().toString());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Path runtimeInputRoot() {
        return workspace.resolve("backend-context/inputs");
    }

    private Path evidenceRoot() {
        return workspace.resolve("backend-context/runtime-data/langchain-state/evidence");
    }

    private Path privateRuntimeRoot() {
        return workspace.resolve("backend-context/runtime-data/private");
    }
}
