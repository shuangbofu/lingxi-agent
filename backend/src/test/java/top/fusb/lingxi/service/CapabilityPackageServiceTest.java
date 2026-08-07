package top.fusb.lingxi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.config.LingxiProperties;
import top.fusb.lingxi.dto.AgentCapabilityResponse;
import top.fusb.lingxi.dto.CapabilityPackageInspectionResponse;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.runtime.capability.CapabilityRuntimeDefinitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityPackageServiceTest {

    @TempDir
    Path tempDir;

    private LingxiProperties properties;
    private AgentCapabilityService agentCapabilityService;
    private CapabilityPackageService service;

    @BeforeEach
    void setUp() {
        properties = new LingxiProperties();
        properties.getDefinitions().setInstalledSkillDir(tempDir.resolve("installed-skills").toString());
        ObjectMapper objectMapper = new ObjectMapper();
        ModuleDefinitionService moduleDefinitionService = new ModuleDefinitionService(objectMapper, properties);
        CapabilityRuntimeDefinitionService runtimeDefinitionService = new CapabilityRuntimeDefinitionService(objectMapper, properties);
        agentCapabilityService = mock(AgentCapabilityService.class);
        service = new CapabilityPackageService(properties, objectMapper, moduleDefinitionService,
                runtimeDefinitionService, agentCapabilityService);
    }

    @Test
    void shouldInspectInstallAndExposeCapabilityRuntimeWithoutRestart() throws Exception {
        MockMultipartFile file = packageFile(validPackageEntries());
        CapabilityPackageInspectionResponse inspection = service.inspect(file);
        AgentCapabilityResponse installed = new AgentCapabilityResponse();
        installed.setName("示例能力");
        when(agentCapabilityService.installOrUpdatePackage(any(), anyString())).thenReturn(installed);

        AgentCapabilityResponse response = service.install(inspection.getStagingToken());

        Path installedRoot = Path.of(properties.getDefinitions().getInstalledSkillDir()).resolve("sample-capability");
        CapabilityRuntimeDefinitionService runtimeDefinitionService = new CapabilityRuntimeDefinitionService(new ObjectMapper(), properties);
        assertThat(inspection.getCode()).isEqualTo("sample-capability");
        assertThat(inspection.getVersion()).isEqualTo("1.2.0");
        assertThat(inspection.getCommands()).singleElement().satisfies(command -> {
            assertThat(command.getCode()).isEqualTo("sample-capability.run");
            assertThat(command.getName()).isEqualTo("运行示例能力");
            assertThat(command.getCommand()).isEqualTo("sample-capability run");
        });
        assertThat(inspection.getRequirements()).containsExactly("requests>=2.31,<3");
        assertThat(response.getName()).isEqualTo("示例能力");
        assertThat(installedRoot.resolve("SKILL.md")).isRegularFile();
        assertThat(installedRoot.resolve("lingxi.json")).isRegularFile();
        assertThat(runtimeDefinitionService.modules()).extracting("moduleCode").contains("sample-capability");
        verify(agentCapabilityService).installOrUpdatePackage(any(), anyString());
    }

    @Test
    void shouldUpdateInstalledPackageAndReplaceRuntimeFiles() throws Exception {
        Path installedRoot = Path.of(properties.getDefinitions().getInstalledSkillDir()).resolve("sample-capability");
        Files.createDirectories(installedRoot);
        writeInstalledPackage(installedRoot, "1.1.0");
        Files.writeString(installedRoot.resolve("old-script.py"), "old");

        CapabilityPackageInspectionResponse inspection = service.inspect(packageFile(validPackageEntries()));
        AgentCapabilityResponse updated = new AgentCapabilityResponse();
        updated.setName("示例能力");
        updated.setPackageVersion("1.2.0");
        when(agentCapabilityService.installOrUpdatePackage(any(), anyString())).thenReturn(updated);

        AgentCapabilityResponse response = service.install(inspection.getStagingToken());

        assertThat(inspection.isUpdate()).isTrue();
        assertThat(inspection.getCurrentVersion()).isEqualTo("1.1.0");
        assertThat(response.getPackageVersion()).isEqualTo("1.2.0");
        assertThat(installedRoot.resolve("old-script.py")).doesNotExist();
        assertThat(installedRoot.resolve("SKILL.md")).isRegularFile();
    }

    @Test
    void shouldRejectArchivePathTraversal() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("../outside.txt", "invalid");
        entries.put("SKILL.md", "---\nname: invalid\ndescription: invalid\n---\ninvalid");

        assertThatThrownBy(() -> service.inspect(packageFile(entries)))
                .isInstanceOfSatisfying(BizException.class,
                        error -> assertThat(error.getErrorSubCode()).isEqualTo(ErrorSubCode.CAPABILITY_PACKAGE_INVALID));
        assertThat(tempDir.resolve("outside.txt")).doesNotExist();
    }

    @Test
    void shouldRejectNonPythonEntrypoint() throws Exception {
        Map<String, String> entries = validPackageEntries();
        entries.computeIfPresent("sample-capability/lingxi.json",
                (path, content) -> content.replace("scripts/sample.py", "scripts/sample.sh"));

        assertThatThrownBy(() -> service.inspect(packageFile(entries)))
                .isInstanceOfSatisfying(BizException.class, error -> {
                    assertThat(error.getErrorSubCode()).isEqualTo(ErrorSubCode.CAPABILITY_PACKAGE_INVALID);
                    assertThat(error.getMessage()).contains("只支持 Python entrypoint");
                });
    }

    private Map<String, String> validPackageEntries() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("sample-capability/SKILL.md", """
                ---
                name: sample-capability
                description: "需要验证能力安装流程时使用。"
                ---

                使用示例命令完成任务。
                """);
        entries.put("sample-capability/lingxi.json", """
                {
                  "schemaVersion": 1,
                  "version": "1.2.0",
                  "presentation": {"displayName": "示例能力"},
                  "enabledByDefault": true,
                  "entrypoint": "scripts/sample.py",
                  "taskParameters": [],
                  "configurationParameters": [],
                  "commands": [{
                    "command": "sample-capability run",
                    "displayName": "运行示例能力"
                  }]
                }
                """);
        entries.put("sample-capability/references/scope.md", "仅处理示例任务。");
        entries.put("sample-capability/scripts/sample.py", "print('ok')");
        entries.put("sample-capability/scripts/requirements.txt", "requests>=2.31,<3\n");
        return entries;
    }

    private void writeInstalledPackage(Path installedRoot, String version) throws Exception {
        Map<String, String> entries = validPackageEntries();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String relativeName = entry.getKey().substring("sample-capability/".length());
            Path target = installedRoot.resolve(relativeName);
            Files.createDirectories(target.getParent());
            Files.writeString(target, entry.getValue().replace("1.2.0", version));
        }
    }

    private MockMultipartFile packageFile(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return new MockMultipartFile("file", "sample-capability.zip", "application/zip", output.toByteArray());
    }
}
