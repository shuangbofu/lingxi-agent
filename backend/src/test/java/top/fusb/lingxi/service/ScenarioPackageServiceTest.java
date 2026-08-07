package top.fusb.lingxi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import top.fusb.lingxi.config.LingxiProperties;
import top.fusb.lingxi.dto.AgentScenarioResponse;
import top.fusb.lingxi.dto.ScenarioPackageInspectionResponse;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScenarioPackageServiceTest {

    @TempDir
    private Path tempDir;

    @Mock
    private AgentScenarioService agentScenarioService;

    private ScenarioPackageService service;

    @BeforeEach
    void setUp() {
        LingxiProperties properties = new LingxiProperties();
        properties.getDefinitions().setInstalledScenarioDir(tempDir.resolve("installed-scenarios").toString());
        ModuleDefinitionService moduleDefinitionService = new ModuleDefinitionService(new ObjectMapper(), properties);
        service = new ScenarioPackageService(properties, moduleDefinitionService,
                agentScenarioService);
    }

    @Test
    void shouldInspectAndInstallScenarioPackage() throws Exception {
        AgentScenarioResponse installed = new AgentScenarioResponse();
        installed.setCode("sample-scenario");
        installed.setName("示例场景");
        when(agentScenarioService.installOrUpdatePackage(any(), anyString())).thenReturn(installed);

        ScenarioPackageInspectionResponse inspection = service.inspect(packageFile(validPackageEntries()));
        AgentScenarioResponse response = service.install(inspection.getStagingToken());

        assertThat(inspection.getCode()).isEqualTo("sample-scenario");
        assertThat(inspection.getVersion()).isEqualTo("1.0.0");
        assertThat(inspection.getCapabilities()).containsExactly("project-hub");
        assertThat(response.getCode()).isEqualTo("sample-scenario");
        verify(agentScenarioService).installOrUpdatePackage(any(), anyString());
    }

    private Map<String, String> validPackageEntries() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("sample-scenario/manifest.json", """
                {
                  "code": "sample-scenario",
                  "version": "1.0.0",
                  "name": "示例场景",
                  "description": "用于验证场景安装流程。",
                  "scenario": "SAMPLE_SCENARIO",
                  "promptFile": "prompt.md",
                  "enabled": true,
                  "capabilities": ["project-hub"],
                  "parameters": []
                }
                """);
        entries.put("sample-scenario/prompt.md", "按真实能力结果完成示例分析。");
        return entries;
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
        return new MockMultipartFile("file", "sample-scenario.zip", "application/zip", output.toByteArray());
    }
}
