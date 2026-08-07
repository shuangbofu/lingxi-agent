package top.fusb.lingxi.task;

import top.fusb.lingxi.config.LingxiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TaskArtifactServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void archivesOnlyLinkedFilesInsideCurrentTaskWorkspace() throws Exception {
        Path workspace = tempDir.resolve("workspaces/task-214");
        Path artifact = workspace.resolve(".agent-task/change.sql");
        Path outside = tempDir.resolve("secret.json");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "select 1;");
        Files.writeString(outside, "secret");

        LingxiProperties properties = new LingxiProperties();
        properties.getTask().setContentDir(tempDir.resolve("content").toString());
        TaskArtifactService service = new TaskArtifactService(new TaskStoragePathService(properties));
        String markdown = "[SQL](" + artifact + ":1) [outside](" + outside + ")";

        String rewritten = service.archiveLinkedFiles(214L, workspace.toString(), markdown);

        assertThat(rewritten).contains("[SQL](/api/agent/tasks/214/artifacts?path=.agent-task%2Fchange.sql)");
        assertThat(rewritten).contains("[outside](" + outside + ")");
        assertThat(Files.readString(tempDir.resolve("content/task-214/artifacts/.agent-task/change.sql"))).isEqualTo("select 1;");

        Path followUpArtifacts = tempDir.resolve("workspaces/task-217/.agent-task/artifacts");
        service.restoreForFollowUp(214L, followUpArtifacts);
        assertThat(Files.readString(followUpArtifacts.resolve("previous-task-214/.agent-task/change.sql"))).isEqualTo("select 1;");
    }

}
