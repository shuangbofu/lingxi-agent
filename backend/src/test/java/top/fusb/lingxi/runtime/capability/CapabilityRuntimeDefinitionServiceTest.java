package top.fusb.lingxi.runtime.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.config.LingxiProperties;
import top.fusb.lingxi.exception.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityRuntimeDefinitionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsStandardSkillScriptsAndUnifiedCommands() throws Exception {
        Path moduleDir = tempDir.resolve("skills/sample");
        Path commandsDir = moduleDir.resolve("scripts");
        Files.createDirectories(commandsDir);
        Files.writeString(moduleDir.resolve("SKILL.md"), """
                ---
                name: sample
                description: "需要读取示例数据时使用。"
                ---

                # Sample
                读取示例数据。
                """);
        Files.writeString(moduleDir.resolve("lingxi.json"), """
                {
                  "schemaVersion": 1,
                  "version": "1.0.0",
                  "presentation": {"displayName": "示例"},
                  "enabledByDefault": true,
                  "entrypoint": "scripts/sample.py",
                  "taskParameters": [],
                  "configurationParameters": [],
                  "commands": [{
                    "command": "sample read",
                    "displayName": "读取示例",
                    "outputs": [{
                      "type": "file-root",
                      "pathField": "worktreePath",
                      "features": ["file-access", "code-index"]
                    }]
                  }]
                }
                """);
        Files.writeString(commandsDir.resolve("sample.py"), "");
        LingxiProperties properties = new LingxiProperties();
        properties.getDefinitions().setInstalledSkillDir(tempDir.resolve("skills").toString());
        CapabilityRuntimeDefinitionService service = new CapabilityRuntimeDefinitionService(new ObjectMapper(), properties);

        assertThat(service.modules()).singleElement().satisfies(module -> {
            assertThat(module.getModuleCode()).isEqualTo("sample");
            assertThat(module.getEntrypoint()).isEqualTo("scripts/sample.py");
            assertThat(module.getModuleDirectory()).isEqualTo(moduleDir.toAbsolutePath().normalize());
        });
        assertThat(service.modules().get(0).getCommands())
                .singleElement()
                .satisfies(command -> assertThat(command.getOutputs().get(0).getFeatures())
                        .containsExactly("file-access", "code-index"));
    }
}
