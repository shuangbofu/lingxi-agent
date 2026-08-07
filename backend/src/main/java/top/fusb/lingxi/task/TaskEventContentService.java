package top.fusb.lingxi.task;

import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.util.FileSystemUtils;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class TaskEventContentService {

    private static final int INLINE_TEXT_LENGTH = 4_000;

    private final TaskStoragePathService taskStoragePathService;

    /**
     * 保存事件大文本，返回给 H2 内嵌保存的摘要。
     *
     * @param taskId 任务 ID
     * @param eventId 事件 ID
     * @param field 字段名
     * @param value 原始文本
     * @return 可直接写入事件 payload 的摘要文本
     * @throws BizException 文件写入失败时抛出
     */
    public String compact(Long taskId, Long eventId, String field, String value) {
        if (value == null || value.length() <= INLINE_TEXT_LENGTH) {
            return value;
        }
        try {
            Path file = filePath(taskId, eventId, field);
            Files.createDirectories(file.getParent());
            Files.writeString(file, value, StandardCharsets.UTF_8);
            return value.substring(0, INLINE_TEXT_LENGTH)
                    + System.lineSeparator()
                    + System.lineSeparator()
                    + "[内容过长，完整内容已写入文件，可在页面查看完整内容；字符数="
                    + value.length()
                    + "，sha256="
                    + sha256(value)
                    + "]";
        } catch (Exception e) {
            throw new BizException(ErrorCode.PROCESS_ERROR, ErrorSubCode.DATA_LOAD_FAILED, "保存任务事件完整内容失败：" + e.getMessage());
        }
    }

    /**
     * 读取事件完整文本。
     *
     * @param taskId 任务 ID
     * @param eventId 事件 ID
     * @param field 字段名
     * @return 完整文本
     * @throws BizException 文件不存在或读取失败时抛出
     */
    public String read(Long taskId, Long eventId, String field) {
        try {
            Path file = filePath(taskId, eventId, field);
            if (!Files.isRegularFile(file)) {
                throw new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.DATA_LOAD_FAILED, "任务事件完整内容不存在");
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.PROCESS_ERROR, ErrorSubCode.DATA_LOAD_FAILED, "读取任务事件完整内容失败：" + e.getMessage());
        }
    }

    /**
     * 判断指定事件字段是否有文件化完整内容。
     *
     * @param taskId 任务 ID
     * @param eventId 事件 ID
     * @param field 字段名
     * @return 是否存在完整内容文件
     */
    public boolean exists(Long taskId, Long eventId, String field) {
        return Files.isRegularFile(filePath(taskId, eventId, field));
    }

    /**
     * 删除指定任务的事件大文本文件，避免重试后读取到上一次失败执行的内容。
     *
     * @param taskId 任务 ID
     * @return 无返回值
     * @throws BizException 文件删除失败时抛出
     */
    public void clearTask(Long taskId) {
        try {
            FileSystemUtils.deleteRecursively(taskStoragePathService.eventRoot(taskId));
        } catch (Exception e) {
            throw new BizException(ErrorCode.PROCESS_ERROR, ErrorSubCode.DATA_LOAD_FAILED, "清理任务事件文件失败：" + e.getMessage());
        }
    }

    private Path filePath(Long taskId, Long eventId, String field) {
        return taskStoragePathService.eventFile(taskId, eventId, fileName(field));
    }

    private String fileName(String field) {
        String safeField = switch (field) {
            case "detail", "arguments", "output", "message", "command", "actionTarget" -> field;
            default -> throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "事件字段不支持读取完整内容");
        };
        return safeField + ".txt";
    }

    private String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
