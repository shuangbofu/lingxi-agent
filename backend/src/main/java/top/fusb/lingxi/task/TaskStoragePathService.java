package top.fusb.lingxi.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.fusb.lingxi.config.LingxiProperties;

import java.nio.file.Path;

@Service
@RequiredArgsConstructor
public class TaskStoragePathService {

    private final LingxiProperties properties;

    /**
     * 返回指定任务统一的持久化目录。
     *
     * @param taskId 任务 ID
     * @return `contentDir/task-<id>` 绝对路径
     */
    public Path taskRoot(Long taskId) {
        return contentRoot().resolve("task-" + taskId).normalize();
    }

    /**
     * 返回指定任务统一的工作区。
     *
     * @param taskId 任务 ID
     * @return `contentDir/task-<id>/workspace` 绝对路径
     */
    public Path workspaceRoot(Long taskId) {
        return taskRoot(taskId).resolve("workspace").normalize();
    }

    /**
     * 返回新事件大字段的写入路径。
     *
     * @param taskId 任务 ID
     * @param eventId 事件 ID
     * @param fileName 已校验的事件文件名
     * @return `task-<id>/events/event-<id>/<fileName>` 绝对路径
     */
    public Path eventFile(Long taskId, Long eventId, String fileName) {
        return taskRoot(taskId)
                .resolve("events")
                .resolve("event-" + eventId)
                .resolve(fileName)
                .normalize();
    }

    /**
     * 返回重试时需要清理的事件目录。
     *
     * @param taskId 任务 ID
     * @return `contentDir/task-<id>/events` 绝对路径
     */
    public Path eventRoot(Long taskId) {
        return taskRoot(taskId).resolve("events").normalize();
    }

    private Path contentRoot() {
        return Path.of(properties.getTask().getContentDir()).toAbsolutePath().normalize();
    }
}
