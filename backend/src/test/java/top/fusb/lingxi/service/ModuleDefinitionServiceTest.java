package top.fusb.lingxi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.config.LingxiProperties;
import top.fusb.lingxi.definition.AnalysisPremiseDefinition;
import top.fusb.lingxi.definition.ModuleDefinition;
import top.fusb.lingxi.enums.ScenarioInputMode;
import top.fusb.lingxi.runtime.capability.CapabilityRuntimeDefinitionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleDefinitionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldResolveRuntimeDeclaredByCapabilityManifest() throws Exception {
        Path moduleDir = tempDir.resolve("skills").resolve("sample");
        Path runtimeDir = moduleDir.resolve("scripts");
        Files.createDirectories(runtimeDir);
        Files.writeString(moduleDir.resolve("SKILL.md"), """
                ---
                name: sample
                description: "用于测试运行时定位。"
                ---

                # Sample
                使用示例运行时。
                """);
        Files.writeString(moduleDir.resolve("lingxi.json"), """
                {
                  "schemaVersion": 1,
                  "version": "1.0.0",
                  "presentation": {"displayName": "示例"},
                  "enabledByDefault": true,
                  "taskParameters": [],
                  "configurationParameters": []
                }
                """);
        Files.writeString(runtimeDir.resolve("entry.py"), "");
        LingxiProperties properties = new LingxiProperties();
        properties.getDefinitions().setInstalledScenarioDir(tempDir.resolve("scenarios").toString());
        properties.getDefinitions().setInstalledSkillDir(tempDir.resolve("skills").toString());
        properties.getDefinitions().setResourceSkillCodes(Set.of("sample"));
        ModuleDefinitionService service = new ModuleDefinitionService(new ObjectMapper(), properties);

        assertThat(service.requireCapabilityRuntime("entry.py")).isEqualTo(runtimeDir.toAbsolutePath().normalize());
    }

    @Test
    void shouldRejectDynamicOptionSourceDeclaredByCapability() throws Exception {
        Path moduleDir = tempDir.resolve("skills/sample");
        Files.createDirectories(moduleDir);
        Files.writeString(moduleDir.resolve("SKILL.md"), """
                ---
                name: sample
                description: "用于测试能力边界。"
                ---

                # Sample
                使用示例能力。
                """);
        Files.writeString(moduleDir.resolve("lingxi.json"), """
                {
                  "schemaVersion": 1,
                  "version": "1.0.0",
                  "presentation": {"displayName": "示例"},
                  "enabledByDefault": true,
                  "taskParameters": [{
                    "key": "source",
                    "name": "来源",
                    "type": "select",
                    "required": true,
                    "description": "来源",
                    "optionSource": {}
                  }],
                  "configurationParameters": []
                }
                """);
        LingxiProperties properties = new LingxiProperties();
        properties.getDefinitions().setInstalledScenarioDir(tempDir.resolve("scenarios").toString());
        properties.getDefinitions().setInstalledSkillDir(tempDir.resolve("skills").toString());
        properties.getDefinitions().setResourceSkillCodes(Set.of("sample"));
        ModuleDefinitionService service = new ModuleDefinitionService(new ObjectMapper(), properties);

        assertThatThrownBy(service::listCapabilities)
                .hasMessageContaining("能力参数不能声明动态选项源");
    }

    @Test
    void shouldLoadPremisesFromClasspath() {
        LingxiProperties properties = new LingxiProperties();
        ModuleDefinitionService service = new ModuleDefinitionService(new ObjectMapper(), properties);

        List<AnalysisPremiseDefinition> definitions = service.listPremises();

        assertThat(definitions).extracting(AnalysisPremiseDefinition::getCode)
                .containsExactly("test-environment", "production-environment", "operation-production-environment");
    }

    @Test
    void shouldLoadACompletelyReplacedScenarioAndCapabilitySet() throws Exception {
        Path scenarioDir = tempDir.resolve("scenarios/incident-triage");
        Path capabilityDir = tempDir.resolve("skills/telemetry-source");
        Path commandsDir = capabilityDir.resolve("scripts");
        Files.createDirectories(scenarioDir);
        Files.createDirectories(commandsDir);
        Files.writeString(scenarioDir.resolve("manifest.json"), """
                {
                  "code": "incident-triage",
                  "name": "事件研判",
                  "scenario": "INCIDENT_TRIAGE",
                  "inputMode": "FORM",
                  "promptFile": "prompt.md",
                  "enabled": true,
                  "sortOrder": 10,
                  "capabilities": ["telemetry-source"]
                }
                """);
        Files.writeString(scenarioDir.resolve("prompt.md"), "按事件证据完成研判。");
        Files.writeString(capabilityDir.resolve("SKILL.md"), """
                ---
                name: telemetry-source
                description: "需要查询遥测数据时使用。"
                ---

                根据参数查询遥测数据。
                """);
        Files.writeString(capabilityDir.resolve("lingxi.json"), """
                {
                  "schemaVersion": 1,
                  "version": "1.0.0",
                  "presentation": {"displayName": "遥测查询"},
                  "enabledByDefault": true,
                  "taskParameters": [],
                  "configurationParameters": []
                }
                """);
        Files.writeString(commandsDir.resolve("telemetry.py"), "");
        LingxiProperties properties = new LingxiProperties();
        properties.getDefinitions().setInstalledScenarioDir(tempDir.resolve("scenarios").toString());
        properties.getDefinitions().setInstalledSkillDir(tempDir.resolve("skills").toString());
        properties.getDefinitions().setResourceSkillCodes(Set.of("telemetry-source"));
        ObjectMapper objectMapper = new ObjectMapper();
        ModuleDefinitionService definitionService = new ModuleDefinitionService(objectMapper, properties);
        CapabilityRuntimeDefinitionService runtimeDefinitionService = new CapabilityRuntimeDefinitionService(objectMapper, properties);

        List<ModuleDefinition> scenarios = definitionService.listInstalledScenarios();
        List<ModuleDefinition> capabilities = definitionService.listCapabilities();
        assertThat(scenarios).extracting(ModuleDefinition::getCode).containsExactly("incident-triage");
        assertThat(scenarios.get(0).getInputMode()).isEqualTo(ScenarioInputMode.FORM);
        assertThat(capabilities).extracting(ModuleDefinition::getCode).containsExactly("telemetry-source");
        assertThat(definitionService.readPrompt(scenarios.get(0))).isEqualTo("按事件证据完成研判。");
        assertThat(runtimeDefinitionService.modules()).extracting("moduleCode")
                .containsExactly("telemetry-source");
    }

    @Test
    void shouldIgnoreScenarioManifestSortOrder() throws Exception {
        Path firstByCode = tempDir.resolve("scenarios/alpha");
        Path firstByManifestOrder = tempDir.resolve("scenarios/zeta");
        Files.createDirectories(firstByCode);
        Files.createDirectories(firstByManifestOrder);
        Files.writeString(firstByCode.resolve("manifest.json"), """
                {"code": "alpha", "sortOrder": 999}
                """);
        Files.writeString(firstByManifestOrder.resolve("manifest.json"), """
                {"code": "zeta", "sortOrder": 1}
                """);
        LingxiProperties properties = new LingxiProperties();
        properties.getDefinitions().setInstalledScenarioDir(tempDir.resolve("scenarios").toString());
        ModuleDefinitionService service = new ModuleDefinitionService(new ObjectMapper(), properties);

        assertThat(service.listInstalledScenarios()).extracting(ModuleDefinition::getCode).containsExactly("alpha", "zeta");
    }
}
