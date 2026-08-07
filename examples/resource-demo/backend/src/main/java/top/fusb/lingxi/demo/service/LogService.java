package top.fusb.lingxi.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.fusb.lingxi.demo.domain.LogEntryEntity;
import top.fusb.lingxi.demo.domain.ProjectEntity;
import top.fusb.lingxi.demo.repository.LogEntryRepository;
import top.fusb.lingxi.demo.repository.ProjectRepository;
import top.fusb.lingxi.demo.web.ApiModels.LogBatchInput;
import top.fusb.lingxi.demo.web.ApiModels.LogInput;
import top.fusb.lingxi.demo.web.ApiModels.LogView;
import top.fusb.lingxi.demo.web.BusinessException;
import top.fusb.lingxi.demo.web.ErrorCode;
import top.fusb.lingxi.demo.web.LogModels.FileItem;
import top.fusb.lingxi.demo.web.LogModels.FileListView;
import top.fusb.lingxi.demo.web.LogModels.HealthView;
import top.fusb.lingxi.demo.web.LogModels.TailView;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogService {

    private static final String ROOT_PREFIX = "/demo/logs";
    private static final int MAX_TAIL_BYTES = 20 * 1024 * 1024;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final LogEntryRepository logEntryRepository;
    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public List<LogView> listAdminLogs(Long projectId, String environmentCode) {
        List<LogEntryEntity> entries;
        if (projectId == null) {
            entries = logEntryRepository.findTop500ByOrderByOccurredAtDesc();
        } else if (environmentCode == null || environmentCode.isBlank()) {
            entries = logEntryRepository.findTop500ByProjectIdOrderByOccurredAtDesc(projectId);
        } else {
            entries = logEntryRepository.findTop500ByProjectIdAndEnvironmentCodeOrderByOccurredAtDesc(projectId, environmentCode);
        }
        return entries.stream().map(this::toLogView).toList();
    }

    @Transactional
    public LogView saveLog(LogInput input) {
        ProjectEntity project = requireProject(input.projectId());
        LogEntryEntity entry = new LogEntryEntity();
        entry.setProjectId(project.getId());
        entry.setEnvironmentCode(input.environmentCode().trim());
        entry.setServiceName(input.serviceName().trim());
        entry.setOccurredAt(input.occurredAt() == null ? LocalDateTime.now() : input.occurredAt());
        entry.setLevel(normalizeLevel(input.level()));
        entry.setTraceId(trimToNull(input.traceId()));
        entry.setContent(input.content().trim());
        entry = logEntryRepository.save(entry);
        log.info("Saved demo log entry logId={}, projectId={}, environment={}, service={}, traceId={}",
                entry.getId(), entry.getProjectId(), entry.getEnvironmentCode(), entry.getServiceName(), entry.getTraceId());
        return toLogView(entry);
    }

    @Transactional
    public List<LogView> saveBatch(LogBatchInput input) {
        List<String> lines = input.content().lines().filter(line -> !line.isBlank()).toList();
        List<LogView> result = new ArrayList<>();
        LocalDateTime baseTime = LocalDateTime.now().minusNanos(lines.size() * 1_000_000L);
        for (int index = 0; index < lines.size(); index++) {
            result.add(saveLog(new LogInput(input.projectId(), input.environmentCode(), input.serviceName(),
                    baseTime.plusNanos(index * 1_000_000L), input.level(), input.traceId(), lines.get(index))));
        }
        log.info("Imported demo log batch projectId={}, environment={}, service={}, count={}",
                input.projectId(), input.environmentCode(), input.serviceName(), result.size());
        return result;
    }

    @Transactional
    public void deleteLog(Long logId) {
        if (!logEntryRepository.existsById(logId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "日志记录不存在");
        }
        logEntryRepository.deleteById(logId);
        log.info("Deleted demo log entry logId={}", logId);
    }

    @Transactional(readOnly = true)
    public HealthView health(String root) {
        List<LogStream> streams = streams(root);
        return new HealthView("UP", normalizeRoot(root), streams.size());
    }

    @Transactional(readOnly = true)
    public FileListView listFiles(String root, String path, String pattern, int limit) {
        String normalizedRoot = normalizeRoot(root);
        String relativePath = normalizeRelative(path);
        String glob = pattern == null || pattern.isBlank() ? "*.log" : pattern;
        List<FileItem> items = streams(normalizedRoot).stream()
                .map(stream -> toFileItem(normalizedRoot, stream))
                .filter(item -> relativePath.isBlank() || item.path().startsWith(relativePath + "/")
                        || item.path().equals(relativePath))
                .filter(item -> FileSystems.getDefault().getPathMatcher("glob:" + glob)
                        .matches(Path.of(item.name())))
                .limit(Math.max(1, Math.min(limit, 500)))
                .toList();
        return new FileListView(normalizedRoot, items);
    }

    @Transactional(readOnly = true)
    public TailView tail(String root, String file, int requestedBytes) {
        String normalizedRoot = normalizeRoot(root);
        String normalizedFile = normalizeRelative(file);
        LogStream stream = streams(normalizedRoot).stream()
                .filter(item -> relativePath(normalizedRoot, item).equals(normalizedFile))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "日志文件不存在"));
        String content = render(stream.entries());
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        int maxBytes = Math.max(1, Math.min(requestedBytes, MAX_TAIL_BYTES));
        if (bytes.length <= maxBytes) {
            return new TailView(normalizedFile, bytes.length, false, content);
        }
        int start = bytes.length - maxBytes;
        while (start < bytes.length && (bytes[start] & 0xC0) == 0x80) {
            start++;
        }
        String tail = new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
        return new TailView(normalizedFile, bytes.length - start, true, tail);
    }

    private List<LogStream> streams(String root) {
        String normalizedRoot = normalizeRoot(root);
        Map<String, List<LogEntryEntity>> grouped = new LinkedHashMap<>();
        for (LogEntryEntity entry : logEntryRepository.findAll()) {
            ProjectEntity project = requireProject(entry.getProjectId());
            String fullPath = ROOT_PREFIX + "/" + project.getCode() + "/" + entry.getEnvironmentCode()
                    + "/" + entry.getServiceName() + ".log";
            if (fullPath.equals(normalizedRoot) || fullPath.startsWith(normalizedRoot + "/")) {
                grouped.computeIfAbsent(fullPath, ignored -> new ArrayList<>()).add(entry);
            }
        }
        return grouped.entrySet().stream()
                .map(entry -> new LogStream(entry.getKey(), entry.getValue().stream()
                        .sorted(Comparator.comparing(LogEntryEntity::getOccurredAt)).toList()))
                .sorted(Comparator.comparing(LogStream::fullPath))
                .toList();
    }

    private FileItem toFileItem(String root, LogStream stream) {
        String relative = relativePath(root, stream);
        String content = render(stream.entries());
        LocalDateTime modifiedAt = stream.entries().get(stream.entries().size() - 1).getOccurredAt();
        return new FileItem(relative, relative.substring(relative.lastIndexOf('/') + 1), false,
                content.getBytes(StandardCharsets.UTF_8).length, modifiedAt);
    }

    private String relativePath(String root, LogStream stream) {
        return stream.fullPath().substring(root.length()).replaceFirst("^/+", "");
    }

    private String render(List<LogEntryEntity> entries) {
        StringBuilder output = new StringBuilder();
        for (LogEntryEntity entry : entries) {
            output.append(LOG_TIME.format(entry.getOccurredAt())).append(' ')
                    .append(String.format("%-5s", entry.getLevel()));
            if (entry.getTraceId() != null) {
                output.append(" [traceId=").append(entry.getTraceId()).append(']');
            }
            output.append(' ').append(entry.getContent()).append('\n');
        }
        return output.toString();
    }

    private LogView toLogView(LogEntryEntity entry) {
        ProjectEntity project = requireProject(entry.getProjectId());
        return new LogView(entry.getId(), entry.getProjectId(), project.getCode(), entry.getEnvironmentCode(),
                entry.getServiceName(), entry.getOccurredAt(), entry.getLevel(), entry.getTraceId(), entry.getContent());
    }

    private ProjectEntity requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_NOT_FOUND, "项目不存在"));
    }

    private String normalizeRoot(String root) {
        String value = root == null || root.isBlank() ? ROOT_PREFIX : root.trim().replace('\\', '/');
        value = value.replaceAll("/+$", "");
        if (!value.equals(ROOT_PREFIX) && !value.startsWith(ROOT_PREFIX + "/")) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "root 必须位于 " + ROOT_PREFIX + " 下");
        }
        if (value.contains("../") || value.endsWith("/..")) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "root 不能包含上级目录");
        }
        return value;
    }

    private String normalizeRelative(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/').replaceFirst("^/+", "");
        if (normalized.contains("../") || normalized.equals("..")) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "相对路径不能包含上级目录");
        }
        return normalized;
    }

    private String normalizeLevel(String level) {
        String value = level == null || level.isBlank() ? "INFO" : level.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "TRACE", "DEBUG", "INFO", "WARN", "ERROR" -> value;
            default -> throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "日志级别不支持");
        };
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record LogStream(String fullPath, List<LogEntryEntity> entries) {
    }
}
