package top.fusb.lingxi.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.fusb.lingxi.config.LingxiProperties;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStoragePathServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void usesUnifiedPathsForNewTaskData() {
        TaskStoragePathService service = service();

        assertThat(service.taskRoot(12L)).isEqualTo(tempDir.resolve("tasks/task-12").toAbsolutePath());
        assertThat(service.workspaceRoot(12L)).isEqualTo(tempDir.resolve("tasks/task-12/workspace").toAbsolutePath());
        assertThat(service.eventFile(12L, 34L, "output.txt"))
                .isEqualTo(tempDir.resolve("tasks/task-12/events/event-34/output.txt").toAbsolutePath());
    }

    private TaskStoragePathService service() {
        LingxiProperties properties = new LingxiProperties();
        properties.getTask().setContentDir(tempDir.resolve("tasks").toString());
        return new TaskStoragePathService(properties);
    }
}
