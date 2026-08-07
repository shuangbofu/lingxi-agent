package top.fusb.lingxi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.fusb.lingxi.config.LingxiProperties;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceSkillInstallerServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldInstallResourceSkillsWithoutRemovingUploadedSkills() throws Exception {
        Path installedRoot = tempDir.resolve("installed-skills");
        Path uploadedSkill = installedRoot.resolve("uploaded-skill");
        Files.createDirectories(uploadedSkill);
        Files.writeString(uploadedSkill.resolve("SKILL.md"), "uploaded");
        LingxiProperties properties = new LingxiProperties();
        properties.getDefinitions().setInstalledSkillDir(installedRoot.toString());
        ResourceSkillInstallerService service = new ResourceSkillInstallerService(properties, new ObjectMapper());

        assertThat(service.install()).containsExactlyInAnyOrder(
                "ask-question", "code-repository", "jdbc-change-execute", "jdbc-readonly-query");
        assertThat(properties.getDefinitions().getResourceSkillCodes()).hasSize(4);
        assertThat(installedRoot.resolve("ask-question/SKILL.md")).isRegularFile();
        assertThat(installedRoot.resolve("code-repository/scripts/code_repository.py")).isRegularFile();
        assertThat(uploadedSkill.resolve("SKILL.md")).hasContent("uploaded");
    }
}
