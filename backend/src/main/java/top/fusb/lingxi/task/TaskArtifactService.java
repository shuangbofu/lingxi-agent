package top.fusb.lingxi.task;

import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskArtifactService {

    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("\\[([^\\]]+)]\\(([^)]+)\\)");
    private static final Pattern LINE_SUFFIX_PATTERN = Pattern.compile(":\\d+(?::\\d+)?$");

    private final TaskStoragePathService taskStoragePathService;

    /**
     * 归档结果中引用的当前任务工作区文件，并将本地路径改写为平台访问地址。
     *
     * @param taskId 任务 ID
     * @param workspacePath 当前任务工作区
     * @param markdown agent 最终输出的 Markdown
     * @return 已改写任务附件链接的 Markdown
     * @throws BizException 归档任务附件失败时抛出
     */
    public String archiveLinkedFiles(Long taskId, String workspacePath, String markdown) {
        if (markdown == null || markdown.isBlank() || workspacePath == null || workspacePath.isBlank()) {
            return markdown;
        }
        Path workspace;
        try {
            workspace = Path.of(workspacePath).toAbsolutePath().normalize().toRealPath();
        } catch (Exception e) {
            return markdown;
        }
        Matcher matcher = MARKDOWN_LINK_PATTERN.matcher(markdown);
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            String destination = matcher.group(2).trim();
            Path source = resolveWorkspaceFile(workspace, destination);
            if (source == null) {
                matcher.appendReplacement(rewritten, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            try {
                Path relative = workspace.relativize(source);
                Path target = artifactRoot(taskId).resolve(relative).normalize();
                if (!target.startsWith(artifactRoot(taskId))) {
                    throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "任务附件路径非法");
                }
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                String href = "/api/agent/tasks/" + taskId + "/artifacts?path="
                        + URLEncoder.encode(relative.toString().replace('\\', '/'), StandardCharsets.UTF_8);
                matcher.appendReplacement(rewritten, Matcher.quoteReplacement("[" + matcher.group(1) + "](" + href + ")"));
                log.info("归档任务附件 taskId={} source={} target={}", taskId, source, target);
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                throw new BizException(ErrorCode.PROCESS_ERROR, ErrorSubCode.DATA_LOAD_FAILED, "归档任务附件失败：" + e.getMessage());
            }
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    /**
     * 读取已归档的任务附件。
     *
     * @param taskId 任务 ID
     * @param relativePath 任务附件相对路径
     * @return 可由接口返回的文件资源
     * @throws BizException 路径非法或附件不存在时抛出
     */
    public Resource read(Long taskId, String relativePath) {
        try {
            Path root = artifactRoot(taskId);
            Path file = root.resolve(relativePath == null ? "" : relativePath).normalize();
            if (!file.startsWith(root)) {
                throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "任务附件路径非法");
            }
            if (!Files.isRegularFile(file)) {
                throw new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.DATA_LOAD_FAILED, "任务附件不存在");
            }
            return new FileSystemResource(file);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.PROCESS_ERROR, ErrorSubCode.DATA_LOAD_FAILED, "读取任务附件失败：" + e.getMessage());
        }
    }

    /**
     * 将上一轮已归档产物复制到新一轮任务工作区，供继续分析或回滚使用。
     *
     * @param sourceTaskId 上一轮任务 ID
     * @param targetDirectory 新一轮任务产物目录
     * @return 无返回值
     * @throws BizException 复制归档产物失败时抛出
     */
    public void restoreForFollowUp(Long sourceTaskId, Path targetDirectory) {
        if (sourceTaskId == null || targetDirectory == null) {
            return;
        }
        Path sourceRoot = artifactRoot(sourceTaskId);
        if (!Files.isDirectory(sourceRoot)) {
            return;
        }
        Path targetRoot = targetDirectory.resolve("previous-task-" + sourceTaskId).toAbsolutePath().normalize();
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            for (Path source : paths.toList()) {
                Path target = targetRoot.resolve(sourceRoot.relativize(source)).normalize();
                if (!target.startsWith(targetRoot)) {
                    throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "任务附件恢复路径非法");
                }
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(source)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            log.info("恢复上一轮任务附件 sourceTaskId={} target={}", sourceTaskId, targetRoot);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.PROCESS_ERROR, ErrorSubCode.DATA_LOAD_FAILED, "恢复上一轮任务附件失败：" + e.getMessage());
        }
    }

    private Path resolveWorkspaceFile(Path workspace, String destination) {
        if (destination.startsWith("http://") || destination.startsWith("https://") || destination.startsWith("/api/")) {
            return null;
        }
        String pathText = LINE_SUFFIX_PATTERN.matcher(destination).replaceFirst("");
        try {
            Path path = Path.of(pathText);
            Path source = (path.isAbsolute() ? path : workspace.resolve(path)).toAbsolutePath().normalize();
            if (!Files.isRegularFile(source)) {
                return null;
            }
            Path realSource = source.toRealPath();
            return realSource.startsWith(workspace) ? realSource : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Path artifactRoot(Long taskId) {
        return taskStoragePathService.taskRoot(taskId).resolve("artifacts").normalize();
    }
}
