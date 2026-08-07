package top.fusb.lingxi.service;

import top.fusb.lingxi.config.LingxiProperties;
import top.fusb.lingxi.dto.UserAvatarUploadResponse;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserAvatarService {

    private static final long MAX_AVATAR_SIZE = 2 * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of(MediaType.IMAGE_PNG_VALUE, MediaType.IMAGE_JPEG_VALUE, "image/webp", "image/gif");

    private final LingxiProperties properties;

    /**
     * 保存用户头像文件并返回可访问地址。
     *
     * @param file 上传的头像文件
     * @return 头像访问地址
     * @throws BizException 文件为空、格式不支持、体积过大或保存失败时抛出
     */
    public UserAvatarUploadResponse upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "请选择头像文件");
        }
        if (file.getSize() > MAX_AVATAR_SIZE) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "头像不能超过 2MB");
        }
        String contentType = file.getContentType();
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "头像仅支持 PNG、JPG、WEBP 或 GIF");
        }
        String filename = UUID.randomUUID() + extension(contentType);
        Path target = avatarRoot().resolve(filename).normalize();
        if (!target.startsWith(avatarRoot())) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "头像文件名无效");
        }
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED, "保存头像失败：" + e.getMessage());
        }
        UserAvatarUploadResponse response = new UserAvatarUploadResponse();
        response.setUrl("/api/public/avatars/" + filename);
        return response;
    }

    public Path avatarPath(String filename) {
        if (filename == null || !filename.matches("[A-Za-z0-9._-]+")) {
            return null;
        }
        Path file = avatarRoot().resolve(filename).normalize().toAbsolutePath().normalize();
        Path root = avatarRoot().toAbsolutePath().normalize();
        return file.startsWith(root) && Files.isRegularFile(file) ? file : null;
    }

    private Path avatarRoot() {
        return Path.of(properties.getUser().getAvatarDir()).toAbsolutePath().normalize();
    }

    private String extension(String contentType) {
        if (MediaType.IMAGE_JPEG_VALUE.equals(contentType)) {
            return ".jpg";
        }
        if ("image/webp".equals(contentType)) {
            return ".webp";
        }
        if ("image/gif".equals(contentType)) {
            return ".gif";
        }
        return ".png";
    }
}
