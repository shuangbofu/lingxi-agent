package top.fusb.lingxi.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.config.LingxiProperties;
import top.fusb.lingxi.dto.TaskAttachmentResponse;
import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskAttachmentService {

    private static final long MAX_ATTACHMENT_SIZE = 50L * 1024 * 1024;
    private static final int MAX_USER_INPUT_LENGTH = 50_000;
    private static final long MAX_USER_INPUT_BYTES = MAX_USER_INPUT_LENGTH * 4L;
    private static final int MAX_ATTACHMENT_COUNT = 5;
    private static final String CONTENT_FILE = "content";
    private static final String METADATA_FILE = "metadata.json";
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/gif", "image/avif", "image/bmp"
    );
    private static final Set<String> ALLOWED_VIDEO_TYPES = Set.of(
            "video/mp4", "video/webm", "video/quicktime", "video/mpeg", "video/x-msvideo", "video/x-matroska"
    );
    private static final Set<String> ALLOWED_TEXT_TYPES = Set.of(
            "text/plain", "text/markdown"
    );
    public static final String USER_INPUT_KIND = "user-input";
    private static final Map<String, String> CONTENT_TYPE_EXTENSIONS = Map.ofEntries(
            Map.entry("image/png", ".png"),
            Map.entry("image/jpeg", ".jpg"),
            Map.entry("image/webp", ".webp"),
            Map.entry("image/gif", ".gif"),
            Map.entry("image/avif", ".avif"),
            Map.entry("image/bmp", ".bmp"),
            Map.entry("video/mp4", ".mp4"),
            Map.entry("video/webm", ".webm"),
            Map.entry("video/quicktime", ".mov"),
            Map.entry("video/mpeg", ".mpeg"),
            Map.entry("video/x-msvideo", ".avi"),
            Map.entry("video/x-matroska", ".mkv"),
            Map.entry("text/plain", ".txt"),
            Map.entry("text/markdown", ".md")
    );

    private final LingxiProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 保存任务输入附件并返回可用于创建任务的附件信息。
     *
     * @param file 用户上传的图片或视频
     * @param inputKind 附件用途标记；user-input 表示超长用户输入文本
     * @param owner 当前登录用户
     * @return 已保存的附件信息
     * @throws BizException 文件为空、类型不支持、体积超限或保存失败时抛出
     */
    public TaskAttachmentResponse upload(MultipartFile file, String inputKind, UserEntity owner) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.TASK_ATTACHMENT_INVALID, "请选择文件");
        }
        if (file.getSize() > MAX_ATTACHMENT_SIZE) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.TASK_ATTACHMENT_INVALID, "单个附件不能超过 50MB");
        }
        String contentType = normalizeContentType(file.getContentType());
        String normalizedKind = inputKind == null ? "" : inputKind.trim();
        if (!USER_INPUT_KIND.equals(normalizedKind)
                && !ALLOWED_IMAGE_TYPES.contains(contentType)
                && !ALLOWED_VIDEO_TYPES.contains(contentType)) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.TASK_ATTACHMENT_INVALID,
                    "仅支持常见图片或视频格式，不支持 SVG");
        }
        if (!USER_INPUT_KIND.equals(normalizedKind) && !normalizedKind.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.TASK_ATTACHMENT_INVALID, "附件用途标记不合法");
        }
        if (USER_INPUT_KIND.equals(normalizedKind) && !ALLOWED_TEXT_TYPES.contains(contentType)) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.TASK_ATTACHMENT_INVALID, "用户输入文本附件格式不合法");
        }
        if (USER_INPUT_KIND.equals(normalizedKind)) {
            if (file.getSize() > MAX_USER_INPUT_BYTES) {
                throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.TASK_ATTACHMENT_INVALID,
                        "输入内容不能超过 50000 个字符");
            }
            try {
                String text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(file.getBytes()))
                        .toString();
                if (text.isBlank() || text.length() > MAX_USER_INPUT_LENGTH) {
                    throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.TASK_ATTACHMENT_INVALID,
                            text.isBlank() ? "用户输入文本不能为空" : "输入内容不能超过 50000 个字符");
                }
            } catch (CharacterCodingException e) {
                throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.TASK_ATTACHMENT_INVALID,
                        "用户输入文本必须使用 UTF-8 编码");
            } catch (IOException e) {
                throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                        "读取用户输入文本失败：" + e.getMessage());
            }
        }
        String attachmentId = UUID.randomUUID().toString();
        TaskAttachmentResponse response = new TaskAttachmentResponse();
        response.setId(attachmentId);
        response.setName(safeOriginalName(file.getOriginalFilename(), contentType));
        response.setContentType(contentType);
        response.setSize(file.getSize());
        response.setInputKind(USER_INPUT_KIND.equals(normalizedKind) ? USER_INPUT_KIND : null);
        response.setUrl("/api/agent/task-attachments/" + attachmentId + "/content");
        Path attachmentDir = attachmentDir(owner.getId(), attachmentId);
        try {
            Files.createDirectories(attachmentDir);
            Files.copy(file.getInputStream(), attachmentDir.resolve(CONTENT_FILE), StandardCopyOption.REPLACE_EXISTING);
            objectMapper.writeValue(attachmentDir.resolve(METADATA_FILE).toFile(), response);
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED, "保存任务附件失败：" + e.getMessage());
        }
        log.info("上传任务附件 attachmentId={} ownerId={} name={} size={} contentType={} inputKind={}",
                attachmentId, owner.getId(), response.getName(), response.getSize(), response.getContentType(),
                response.getInputKind());
        return response;
    }

    /**
     * 将后端自动识别的超长用户输入保存为 UTF-8 文本附件。
     *
     * @param content 用户输入全文
     * @param owner 当前登录用户
     * @return 已保存的全文附件信息
     * @throws BizException 内容为空、体积超限或保存失败时抛出
     */
    public TaskAttachmentResponse createUserInputAttachment(String content, UserEntity owner) {
        String normalizedContent = content == null ? "" : content.trim();
        if (normalizedContent.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.TASK_ATTACHMENT_INVALID, "用户输入文本不能为空");
        }
        if (normalizedContent.length() > MAX_USER_INPUT_LENGTH) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.TASK_ATTACHMENT_INVALID,
                    "输入内容不能超过 50000 个字符");
        }
        byte[] bytes = normalizedContent.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_ATTACHMENT_SIZE) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.TASK_ATTACHMENT_INVALID, "单个附件不能超过 50MB");
        }
        String attachmentId = UUID.randomUUID().toString();
        TaskAttachmentResponse response = new TaskAttachmentResponse();
        response.setId(attachmentId);
        String title = normalizedContent.replaceAll("\\s+", " ");
        response.setName(safeAttachmentName(title.substring(0, Math.min(title.length(), 32)) + ".txt"));
        response.setContentType("text/plain");
        response.setSize((long) bytes.length);
        response.setInputKind(USER_INPUT_KIND);
        response.setUrl("/api/agent/task-attachments/" + attachmentId + "/content");
        Path attachmentDir = attachmentDir(owner.getId(), attachmentId);
        try {
            Files.createDirectories(attachmentDir);
            Files.write(attachmentDir.resolve(CONTENT_FILE), bytes);
            objectMapper.writeValue(attachmentDir.resolve(METADATA_FILE).toFile(), response);
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED, "保存任务附件失败：" + e.getMessage());
        }
        log.info("自动保存用户输入全文 attachmentId={} ownerId={} size={}",
                attachmentId, owner.getId(), response.getSize());
        return response;
    }

    /**
     * 校验附件列表属于当前用户并读取其元数据。
     *
     * @param attachmentIds 客户端提交的附件 ID 列表
     * @param owner 当前登录用户
     * @return 去重后的附件信息列表
     * @throws BizException 附件数量超限、ID 无效或附件不存在时抛出
     */
    public List<TaskAttachmentResponse> resolve(List<String> attachmentIds, UserEntity owner) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>(attachmentIds);
        if (ids.size() > MAX_ATTACHMENT_COUNT + 1) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.TASK_ATTACHMENT_INVALID,
                    "每次最多上传 5 个普通附件和 1 个全文附件");
        }
        List<TaskAttachmentResponse> attachments = ids.stream()
                .map(id -> readMetadata(owner.getId(), id))
                .toList();
        long regularCount = attachments.stream()
                .filter(attachment -> !USER_INPUT_KIND.equals(attachment.getInputKind()))
                .count();
        if (regularCount > MAX_ATTACHMENT_COUNT) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.TASK_ATTACHMENT_INVALID, "每次最多上传 5 个附件");
        }
        long userInputCount = attachments.size() - regularCount;
        if (userInputCount > 1) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.TASK_ATTACHMENT_INVALID, "每次只能包含 1 个全文附件");
        }
        return attachments;
    }

    /**
     * 读取当前用户附件的预览内容。
     *
     * @param attachmentId 附件 ID
     * @param owner 当前登录用户
     * @return 附件资源及元数据
     * @throws BizException 附件 ID 无效或文件不存在时抛出
     */
    public AttachmentContent read(String attachmentId, UserEntity owner) {
        TaskAttachmentResponse attachment = readMetadata(owner.getId(), attachmentId);
        Path file = attachmentDir(owner.getId(), attachmentId).resolve(CONTENT_FILE);
        if (!Files.isRegularFile(file)) {
            throw new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.TASK_ATTACHMENT_NOT_FOUND);
        }
        return new AttachmentContent(new FileSystemResource(file), attachment);
    }

    /**
     * 将已校验附件复制到任务工作区，使用纯 ASCII 稳定文件名隔离原始名称和文件系统路径。
     *
     * @param attachments 需要复制的附件列表
     * @param ownerId 附件所属用户 ID
     * @param targetDir 工作区附件目录
     * @return 工作区内的附件文件名列表
     * @throws IOException 创建目录或复制文件失败时抛出
     */
    public List<String> copyToWorkspace(List<TaskAttachmentResponse> attachments, Long ownerId, Path targetDir) throws IOException {
        if (attachments == null || attachments.isEmpty() || ownerId == null) {
            return List.of();
        }
        Files.createDirectories(targetDir);
        List<String> filenames = new ArrayList<>();
        int index = 1;
        for (TaskAttachmentResponse attachment : attachments) {
            TaskAttachmentResponse stored = readMetadata(ownerId, attachment.getId());
            String attachmentKey = stored.getId().replace("-", "").substring(0, 12);
            String filename = "%02d-attachment-%s%s".formatted(index++, attachmentKey,
                    CONTENT_TYPE_EXTENSIONS.getOrDefault(stored.getContentType(), ".bin"));
            Path source = attachmentDir(ownerId, stored.getId()).resolve(CONTENT_FILE);
            Files.copy(source, targetDir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
            filenames.add(filename);
        }
        return filenames;
    }

    private TaskAttachmentResponse readMetadata(Long ownerId, String attachmentId) {
        requireAttachmentId(attachmentId);
        Path metadata = attachmentDir(ownerId, attachmentId).resolve(METADATA_FILE);
        if (!Files.isRegularFile(metadata)) {
            throw new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.TASK_ATTACHMENT_NOT_FOUND);
        }
        try {
            return objectMapper.readValue(metadata.toFile(), TaskAttachmentResponse.class);
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED, "读取任务附件失败：" + e.getMessage());
        }
    }

    private Path attachmentDir(Long ownerId, String attachmentId) {
        Path ownerRoot = attachmentRoot().resolve(String.valueOf(ownerId)).normalize();
        Path result = ownerRoot.resolve(attachmentId).normalize();
        if (!result.startsWith(ownerRoot)) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.TASK_ATTACHMENT_INVALID, "附件 ID 无效");
        }
        return result;
    }

    private Path attachmentRoot() {
        return Path.of(properties.getTask().getContentDir()).resolve("input-attachments").toAbsolutePath().normalize();
    }

    private void requireAttachmentId(String attachmentId) {
        try {
            UUID.fromString(attachmentId);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.TASK_ATTACHMENT_INVALID, "附件 ID 无效");
        }
    }

    private String normalizeContentType(String contentType) {
        return contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private String safeOriginalName(String originalName, String contentType) {
        String normalized = originalName == null ? "" : originalName.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        name = safeAttachmentName(name);
        if (!name.isBlank()) {
            return name;
        }
        if (ALLOWED_IMAGE_TYPES.contains(contentType)) {
            return "图片";
        }
        return ALLOWED_VIDEO_TYPES.contains(contentType) ? "视频" : "文本";
    }

    private String safeAttachmentName(String value) {
        String name = value == null ? "" : value.replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "_").trim();
        if (name.isBlank()) {
            return "附件";
        }
        return name.length() > 180 ? name.substring(name.length() - 180) : name;
    }

    public record AttachmentContent(Resource resource, TaskAttachmentResponse attachment) {
    }
}
