package top.fusb.lingxi.task;

import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.kit.TextKit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Service
@RequiredArgsConstructor
public class TaskContentService {

    private static final int SUMMARY_TEXT_LENGTH = 1_000;

    private final TaskStoragePathService taskStoragePathService;

    /**
     * 追加任务过程文本到文件。
     *
     * @param taskId 任务 ID
     * @param field 字段名
     * @param value 追加内容
     * @return 写入数据库的摘要文本
     * @throws BizException 文件写入失败时抛出
     */
    public String append(Long taskId, String field, String value) {
        String text = TextKit.blankToNull(value);
        if (text == null) {
            return summary(field);
        }
        try {
            Path file = filePath(taskId, field);
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return summary(field);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PROCESS_ERROR, ErrorSubCode.DATA_LOAD_FAILED, "写入任务输出文件失败：" + e.getMessage());
        }
    }

    /**
     * 覆盖任务文本文件。
     *
     * @param taskId 任务 ID
     * @param field 字段名
     * @param value 完整内容
     * @return 写入数据库的摘要文本
     * @throws BizException 文件写入失败时抛出
     */
    public String write(Long taskId, String field, String value) {
        String text = value == null ? "" : value;
        try {
            Path file = filePath(taskId, field);
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return text.isBlank() ? "" : summary(field) + "\n\n" + TextKit.limit(text, SUMMARY_TEXT_LENGTH);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PROCESS_ERROR, ErrorSubCode.DATA_LOAD_FAILED, "写入任务输出文件失败：" + e.getMessage());
        }
    }

    /**
     * 文件存在时保留已追加内容，不存在时写入给定内容。
     *
     * @param taskId 任务 ID
     * @param field 字段名
     * @param value 完整内容
     * @return 写入数据库的摘要文本
     * @throws BizException 文件写入失败时抛出
     */
    public String writeIfAbsent(Long taskId, String field, String value) {
        try {
            Path file = filePath(taskId, field);
            if (Files.isRegularFile(file)) {
                return summary(field);
            }
            return write(taskId, field, value);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.PROCESS_ERROR, ErrorSubCode.DATA_LOAD_FAILED, "写入任务输出文件失败：" + e.getMessage());
        }
    }

    /**
     * 读取任务文本文件，文件不存在时返回数据库原始内容。
     *
     * @param taskId 任务 ID
     * @param field 字段名
     * @param fallback 数据库原始内容
     * @return 完整内容
     * @throws BizException 文件读取失败时抛出
     */
    public String read(Long taskId, String field, String fallback) {
        try {
            Path file = filePath(taskId, field);
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : fallback;
        } catch (Exception e) {
            throw new BizException(ErrorCode.PROCESS_ERROR, ErrorSubCode.DATA_LOAD_FAILED, "读取任务输出文件失败：" + e.getMessage());
        }
    }

    /**
     * 清空一次失败执行留下的过程、错误和结果文件，供同一任务重新执行。
     *
     * @param taskId 任务 ID
     * @return 无返回值
     * @throws BizException 文件清理失败时抛出
     */
    public void clear(Long taskId) {
        write(taskId, "stdout", "");
        write(taskId, "stderr", "");
        write(taskId, "result", "");
    }

    private Path filePath(Long taskId, String field) {
        String fileName = switch (field) {
            case "stdout" -> "stdout.txt";
            case "stderr" -> "stderr.txt";
            case "result" -> "result.md";
            default -> throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "任务输出字段不支持");
        };
        Path root = taskStoragePathService.taskRoot(taskId);
        Path file = root.resolve(fileName).normalize();
        if (!file.startsWith(root)) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "任务输出文件路径非法");
        }
        return file;
    }

    private String summary(String field) {
        return switch (field) {
            case "stdout" -> "[过程输出已写入文件]";
            case "stderr" -> "[错误输出已写入文件]";
            case "result" -> "[分析结果已写入文件]";
            default -> "[任务输出已写入文件]";
        };
    }
}
