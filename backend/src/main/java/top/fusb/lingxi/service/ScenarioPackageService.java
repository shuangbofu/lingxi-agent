package top.fusb.lingxi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import top.fusb.lingxi.config.LingxiProperties;
import top.fusb.lingxi.definition.ModuleDefinition;
import top.fusb.lingxi.dto.AgentScenarioResponse;
import top.fusb.lingxi.dto.ScenarioPackageInspectionResponse;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScenarioPackageService {

    private static final long MAX_ARCHIVE_SIZE = 10L * 1024 * 1024;
    private static final long MAX_EXTRACTED_SIZE = 20L * 1024 * 1024;
    private static final int MAX_ENTRY_COUNT = 100;
    private static final Duration STAGING_TTL = Duration.ofHours(24);
    private static final String MANIFEST_FILE = "manifest.json";
    private static final String STAGED_PACKAGE_DIR = "package";
    private static final String PACKAGE_HASH_FILE = "package.sha256";

    private final LingxiProperties properties;
    private final ModuleDefinitionService moduleDefinitionService;
    private final AgentScenarioService agentScenarioService;

    /**
     * 上传、解压并校验场景安装包。
     *
     * @param file ZIP 格式场景安装包
     * @return 场景包预览及临时安装凭证
     * @throws BizException 文件为空、超限、结构非法或同编码场景不可更新时抛出
     */
    public ScenarioPackageInspectionResponse inspect(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw packageError("请选择场景安装包");
        }
        if (file.getSize() > MAX_ARCHIVE_SIZE) {
            throw packageError("场景安装包不能超过 10MB");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(".zip")) {
            throw packageError("场景安装包仅支持 ZIP 格式");
        }

        cleanupExpiredStaging();
        String stagingToken = UUID.randomUUID().toString();
        Path stagingDir = stagingRoot().resolve(stagingToken).normalize();
        Path archive = stagingDir.resolve("package.zip");
        Path extracted = stagingDir.resolve("extracted");
        try {
            Files.createDirectories(extracted);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(new BufferedInputStream(file.getInputStream()), digest)) {
                Files.copy(input, archive, StandardCopyOption.REPLACE_EXISTING);
            }
            String packageHash = HexFormat.of().formatHex(digest.digest());
            extractArchive(archive, extracted);
            Path packageRoot = locatePackageRoot(extracted);
            Path stagedRoot = stagingDir.resolve(STAGED_PACKAGE_DIR);
            Files.createDirectories(stagedRoot);
            Path stagedPackage = stagedRoot.resolve(packageRoot.getFileName().toString());
            Files.move(packageRoot, stagedPackage, StandardCopyOption.REPLACE_EXISTING);
            deleteDirectory(extracted);
            Files.deleteIfExists(archive);
            Files.writeString(stagingDir.resolve(PACKAGE_HASH_FILE), packageHash, StandardCharsets.UTF_8);

            ModuleDefinition definition = validatePackage(stagedPackage);
            Optional<ModuleDefinition> existing = existingUpdatableScenario(definition.getCode());
            ScenarioPackageInspectionResponse response = inspectionResponse(
                    stagingToken, file.getSize(), packageHash, definition, existing);
            log.info("Inspected scenario package code={} version={} size={} hash={}",
                    definition.getCode(), definition.getVersion(), file.getSize(), packageHash);
            return response;
        } catch (BizException exception) {
            deleteDirectoryQuietly(stagingDir);
            throw exception;
        } catch (Exception exception) {
            deleteDirectoryQuietly(stagingDir);
            log.info("Inspect scenario package failed file={} message={}", originalName, exception.getMessage());
            throw packageError("读取场景安装包失败：" + exception.getMessage());
        }
    }

    /**
     * 再次校验预览包并安装或更新外置场景。
     *
     * @param stagingToken 预检接口返回的临时安装凭证
     * @return 安装或更新后的场景
     * @throws BizException 预览失效、同编码场景不可更新或文件落盘失败时抛出
     */
    public synchronized AgentScenarioResponse install(String stagingToken) {
        if (stagingToken == null || !stagingToken.matches("[0-9a-fA-F-]{36}")) {
            throw stagingNotFound();
        }
        Path stagingDir = stagingRoot().resolve(stagingToken).normalize();
        Path stagedRoot = stagingDir.resolve(STAGED_PACKAGE_DIR);
        Path hashFile = stagingDir.resolve(PACKAGE_HASH_FILE);
        if (!stagingDir.startsWith(stagingRoot()) || !Files.isDirectory(stagedRoot) || !Files.isRegularFile(hashFile)) {
            throw stagingNotFound();
        }

        Path target = null;
        Path backup = null;
        boolean targetReplaced = false;
        try {
            Path stagedPackage = locatePackageRoot(stagedRoot);
            ModuleDefinition definition = validatePackage(stagedPackage);
            boolean updating = existingUpdatableScenario(definition.getCode()).isPresent();
            String packageHash = Files.readString(hashFile, StandardCharsets.UTF_8).trim();
            Path installRoot = installedRoot();
            Files.createDirectories(installRoot);
            target = installRoot.resolve(definition.getCode()).normalize();
            if (!target.startsWith(installRoot)) {
                throw packageExists(definition.getCode());
            }
            if (Files.exists(target)) {
                if (!updating) {
                    throw packageExists(definition.getCode());
                }
                backup = stagingDir.resolve("previous-package");
                moveDirectory(target, backup);
            }
            moveDirectory(stagedPackage, target);
            targetReplaced = true;
            ModuleDefinition installedDefinition = moduleDefinitionService.inspectScenarioDirectory(target);
            AgentScenarioResponse response = agentScenarioService.installOrUpdatePackage(installedDefinition, packageHash);
            deleteDirectoryQuietly(stagingDir);
            log.info("Scenario package {} completed code={} version={} path={}",
                    updating ? "update" : "installation", installedDefinition.getCode(),
                    installedDefinition.getVersion(), target);
            return response;
        } catch (BizException exception) {
            if (targetReplaced && target != null && Files.exists(target)) {
                deleteDirectoryQuietly(target);
            }
            restoreBackup(backup, target);
            throw exception;
        } catch (Exception exception) {
            if (targetReplaced && target != null && Files.exists(target)) {
                deleteDirectoryQuietly(target);
            }
            restoreBackup(backup, target);
            log.info("Install scenario package failed token={} message={}", stagingToken, exception.getMessage());
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    "安装场景失败：" + exception.getMessage());
        }
    }

    private ModuleDefinition validatePackage(Path packageRoot) {
        ModuleDefinition definition = moduleDefinitionService.inspectScenarioDirectory(packageRoot);
        if (definition.getCode() == null || !definition.getCode().matches("[a-z0-9]+(?:-[a-z0-9]+)*")
                || definition.getCode().length() > 64) {
            throw packageError("manifest.json code 必须是最多 64 位的小写连字符格式");
        }
        if (!definition.getCode().equals(packageRoot.getFileName().toString())) {
            throw packageError("场景编码必须与安装包目录名一致");
        }
        if (definition.getVersion() == null || !definition.getVersion().matches("[0-9A-Za-z][0-9A-Za-z._-]{0,39}")) {
            throw packageError("manifest.json version 不能为空且格式无效");
        }
        if (definition.getName() == null || definition.getName().isBlank()
                || definition.getScenario() == null || definition.getScenario().isBlank()) {
            throw packageError("manifest.json name 和 scenario 不能为空");
        }
        if (moduleDefinitionService.readPrompt(definition).isBlank()) {
            throw packageError("场景提示词不能为空");
        }
        if (definition.getIcon() != null && !definition.getIcon().isBlank()) {
            Path icon = packageRoot.resolve(definition.getIcon()).normalize();
            if (!icon.startsWith(packageRoot) || !Files.isRegularFile(icon)) {
                throw packageError("场景图标文件不存在：" + definition.getIcon());
            }
        }
        return definition;
    }

    private Optional<ModuleDefinition> existingUpdatableScenario(String code) {
        return moduleDefinitionService.listInstalledScenarios().stream()
                .filter(item -> code.equals(item.getCode()))
                .findFirst();
    }

    private ScenarioPackageInspectionResponse inspectionResponse(String stagingToken, long packageSize,
                                                                 String packageHash, ModuleDefinition definition,
                                                                 Optional<ModuleDefinition> existing) {
        ScenarioPackageInspectionResponse response = new ScenarioPackageInspectionResponse();
        response.setStagingToken(stagingToken);
        response.setCode(definition.getCode());
        response.setName(definition.getName());
        response.setVersion(definition.getVersion());
        response.setDescription(definition.getDescription());
        response.setColor(definition.getColor());
        response.setUpdate(existing.isPresent());
        response.setCurrentVersion(existing.map(ModuleDefinition::getVersion).orElse(null));
        response.setPackageSize(packageSize);
        response.setPackageHash(packageHash);
        response.setParameterCount(definition.getParameters() == null ? 0 : definition.getParameters().size());
        response.setCapabilities(definition.getCapabilities() == null
                ? new java.util.LinkedHashSet<>() : new java.util.LinkedHashSet<>(definition.getCapabilities()));
        return response;
    }

    private void extractArchive(Path archive, Path targetRoot) throws IOException {
        long extractedSize = 0;
        int entryCount = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ENTRY_COUNT) {
                    throw packageError("场景安装包文件数量不能超过 " + MAX_ENTRY_COUNT);
                }
                String entryName = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
                if (entryName.isBlank() || entryName.startsWith("/") || entryName.contains("\u0000")) {
                    throw packageError("场景安装包包含非法文件路径");
                }
                Path destination = targetRoot.resolve(entryName).normalize();
                if (!destination.startsWith(targetRoot)) {
                    throw packageError("场景安装包包含越界文件路径：" + entryName);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }
                Files.createDirectories(destination.getParent());
                try (OutputStream output = Files.newOutputStream(destination)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        extractedSize += count;
                        if (extractedSize > MAX_EXTRACTED_SIZE) {
                            throw packageError("场景安装包解压后不能超过 20MB");
                        }
                        output.write(buffer, 0, count);
                    }
                }
            }
        }
        if (entryCount == 0) {
            throw packageError("场景安装包为空");
        }
        try (var paths = Files.walk(targetRoot)) {
            if (paths.anyMatch(Files::isSymbolicLink)) {
                throw packageError("场景安装包不能包含符号链接");
            }
        }
    }

    private Path locatePackageRoot(Path extractedRoot) throws IOException {
        if (Files.isRegularFile(extractedRoot.resolve(MANIFEST_FILE))) {
            return extractedRoot;
        }
        try (var paths = Files.list(extractedRoot)) {
            List<Path> candidates = paths.filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .filter(path -> Files.isRegularFile(path.resolve(MANIFEST_FILE)))
                    .toList();
            if (candidates.size() != 1) {
                throw packageError("ZIP 根目录或唯一一级子目录中必须包含 manifest.json");
            }
            return candidates.get(0);
        }
    }

    private void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void restoreBackup(Path backup, Path target) {
        if (backup == null || target == null || !Files.exists(backup)) {
            return;
        }
        try {
            moveDirectory(backup, target);
        } catch (IOException exception) {
            log.error("Restore previous scenario package failed backup={} target={} message={}",
                    backup, target, exception.getMessage());
        }
    }

    private void cleanupExpiredStaging() {
        Path root = stagingRoot();
        if (!Files.isDirectory(root)) {
            return;
        }
        Instant expireBefore = Instant.now().minus(STAGING_TTL);
        try (var paths = Files.list(root)) {
            paths.filter(Files::isDirectory).forEach(path -> {
                try {
                    FileTime modified = Files.getLastModifiedTime(path);
                    if (modified.toInstant().isBefore(expireBefore)) {
                        deleteDirectory(path);
                    }
                } catch (IOException exception) {
                    log.info("Clean scenario staging failed path={} message={}", path, exception.getMessage());
                }
            });
        } catch (IOException exception) {
            log.info("Scan scenario staging failed path={} message={}", root, exception.getMessage());
        }
    }

    private Path installedRoot() {
        return Path.of(properties.getDefinitions().getInstalledScenarioDir()).toAbsolutePath().normalize();
    }

    private Path stagingRoot() {
        return installedRoot().resolve(".staging").normalize();
    }

    private void deleteDirectoryQuietly(Path root) {
        try {
            deleteDirectory(root);
        } catch (IOException exception) {
            log.info("Delete scenario directory failed path={} message={}", root, exception.getMessage());
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

    private BizException packageError(String message) {
        return new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.SCENARIO_PACKAGE_INVALID, message);
    }

    private BizException packageExists(String code) {
        return new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.SCENARIO_PACKAGE_EXISTS,
                "场景编码已存在：" + code);
    }

    private BizException stagingNotFound() {
        return new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.SCENARIO_PACKAGE_STAGING_NOT_FOUND,
                "场景安装预览已失效，请重新上传");
    }
}
