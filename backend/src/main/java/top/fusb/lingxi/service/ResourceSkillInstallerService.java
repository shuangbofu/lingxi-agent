package top.fusb.lingxi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import top.fusb.lingxi.config.LingxiProperties;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceSkillInstallerService {

    private static final String RESOURCE_PATTERN = "classpath*:skills/**/*";
    private static final String RESOURCE_PATH_MARKER = "/skills/";
    private static final String SKILL_FILE = "SKILL.md";
    private static final String RESOURCE_INDEX_FILE = ".resource-skills.json";
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private final LingxiProperties properties;
    private final ObjectMapper objectMapper;
    private final ResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    /**
     * 将 resources 中的默认 Skill 同步到统一的 installed-skills 目录。
     *
     * @return 本次同步的默认 Skill 编码
     * @throws IllegalStateException 资源读取、目录替换或索引写入失败时抛出
     */
    public Set<String> install() {
        Path installRoot = Path.of(properties.getDefinitions().getInstalledSkillDir()).toAbsolutePath().normalize();
        Path staging = installRoot.resolve(".staging").resolve("resources-" + UUID.randomUUID()).normalize();
        try {
            Files.createDirectories(staging);
            Set<String> currentCodes = copyResources(staging);
            Set<String> previousCodes = readPreviousCodes(installRoot);
            for (String code : previousCodes) {
                if (!currentCodes.contains(code)) {
                    deleteDirectory(installRoot.resolve(code));
                }
            }
            for (String code : currentCodes) {
                Path target = installRoot.resolve(code).normalize();
                if (!target.startsWith(installRoot)) {
                    throw new IOException("默认 Skill 安装路径越界: " + code);
                }
                deleteDirectory(target);
                moveDirectory(staging.resolve(code), target);
            }
            objectMapper.writeValue(installRoot.resolve(RESOURCE_INDEX_FILE).toFile(), currentCodes);
            Set<String> installedCodes = Set.copyOf(currentCodes);
            properties.getDefinitions().setResourceSkillCodes(installedCodes);
            log.info("Installed resource Skills codes={} target={}", installedCodes, installRoot);
            return installedCodes;
        } catch (IOException exception) {
            throw new IllegalStateException("安装 resources 中的 Skill 失败: " + exception.getMessage(), exception);
        } finally {
            deleteDirectoryQuietly(staging);
        }
    }

    private Set<String> copyResources(Path staging) throws IOException {
        Set<String> skillCodes = new LinkedHashSet<>();
        for (Resource resource : resourceResolver.getResources(RESOURCE_PATTERN)) {
            if (!resource.isReadable()) {
                continue;
            }
            Path relative = resourceRelativePath(resource);
            Path targetFile = staging.resolve(relative).normalize();
            if (!targetFile.startsWith(staging)) {
                throw new IOException("Skill 资源路径越界: " + relative);
            }
            Files.createDirectories(targetFile.getParent());
            try (InputStream input = resource.getInputStream()) {
                Files.copy(input, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
            if (relative.getNameCount() == 2 && SKILL_FILE.equals(relative.getFileName().toString())) {
                String code = relative.getName(0).toString();
                if (!CODE_PATTERN.matcher(code).matches()) {
                    throw new IOException("默认 Skill 目录名无效: " + code);
                }
                skillCodes.add(code);
            }
        }
        if (skillCodes.isEmpty()) {
            throw new IOException("classpath 中没有可用的默认 Skill");
        }
        return skillCodes;
    }

    private Set<String> readPreviousCodes(Path installRoot) throws IOException {
        Path index = installRoot.resolve(RESOURCE_INDEX_FILE);
        if (!Files.isRegularFile(index)) {
            return Set.of();
        }
        Set<String> codes = objectMapper.readValue(index.toFile(), new TypeReference<>() {
        });
        for (String code : codes) {
            if (!CODE_PATTERN.matcher(code).matches()) {
                throw new IOException("默认 Skill 索引包含无效编码: " + code);
            }
        }
        return codes;
    }

    private Path resourceRelativePath(Resource resource) throws IOException {
        String location = resource.getURL().toExternalForm();
        int markerIndex = location.lastIndexOf(RESOURCE_PATH_MARKER);
        if (markerIndex < 0) {
            throw new IOException("无法识别 Skill 资源路径: " + resource.getDescription());
        }
        return Path.of(location.substring(markerIndex + RESOURCE_PATH_MARKER.length())).normalize();
    }

    private void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void deleteDirectoryQuietly(Path root) {
        try {
            deleteDirectory(root);
        } catch (IOException exception) {
            log.info("Clean resource Skill staging failed path={} message={}", root, exception.getMessage());
        }
    }

    private void deleteDirectory(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
