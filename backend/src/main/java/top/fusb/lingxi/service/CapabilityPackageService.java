package top.fusb.lingxi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.config.LingxiProperties;
import top.fusb.lingxi.definition.CapabilityCommandExtensionDefinition;
import top.fusb.lingxi.definition.LingxiCapabilityDefinition;
import top.fusb.lingxi.definition.ModuleDefinition;
import top.fusb.lingxi.dto.AgentCapabilityResponse;
import top.fusb.lingxi.dto.CapabilityCommandDefinition;
import top.fusb.lingxi.dto.CapabilityCommandOutputDefinition;
import top.fusb.lingxi.dto.CapabilityPackageCommandResponse;
import top.fusb.lingxi.dto.CapabilityPackageInspectionResponse;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.runtime.capability.CapabilityRuntimeDefinitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class CapabilityPackageService {

    private static final long MAX_ARCHIVE_SIZE = 20L * 1024 * 1024;
    private static final long MAX_EXTRACTED_SIZE = 50L * 1024 * 1024;
    private static final int MAX_ENTRY_COUNT = 500;
    private static final Duration STAGING_TTL = Duration.ofHours(24);
    private static final String SKILL_FILE = "SKILL.md";
    private static final String SCRIPTS_DIR = "scripts";
    private static final String STAGED_PACKAGE_DIR = "package";
    private static final String PACKAGE_HASH_FILE = "package.sha256";

    private final LingxiProperties properties;
    private final ObjectMapper objectMapper;
    private final ModuleDefinitionService moduleDefinitionService;
    private final CapabilityRuntimeDefinitionService capabilityRuntimeDefinitionService;
    private final AgentCapabilityService agentCapabilityService;

    /**
     * 上传、解压并校验能力安装包，返回确认安装所需的预览信息。
     *
     * @param file ZIP 格式能力安装包
     * @return 能力包预览及临时安装凭证
     * @throws BizException 文件为空、超限、结构非法或同编码能力不可更新时抛出
     */
    public CapabilityPackageInspectionResponse inspect(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw packageError("请选择能力安装包");
        }
        if (file.getSize() > MAX_ARCHIVE_SIZE) {
            throw packageError("能力安装包不能超过 20MB");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(".zip")) {
            throw packageError("能力安装包仅支持 ZIP 格式");
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
            if (Files.exists(extracted)) {
                deleteDirectory(extracted);
            }
            Files.deleteIfExists(archive);
            Files.writeString(stagingDir.resolve(PACKAGE_HASH_FILE), packageHash, StandardCharsets.UTF_8);

            PackageInspection inspection = validatePackage(stagedPackage);
            Optional<ModuleDefinition> existing =
                    existingUpdatableCapability(inspection.definition().getCode());
            log.info("Inspected capability package code={} version={} size={} hash={}",
                    inspection.definition().getCode(), inspection.definition().getVersion(), file.getSize(), packageHash);
            return inspectionResponse(stagingToken, file.getSize(), packageHash, inspection, existing);
        } catch (BizException e) {
            deleteDirectoryQuietly(stagingDir);
            throw e;
        } catch (Exception e) {
            deleteDirectoryQuietly(stagingDir);
            log.info("Inspect capability package failed file={} message={}", originalName, e.getMessage());
            throw packageError("读取能力安装包失败：" + e.getMessage());
        }
    }

    /**
     * 再次校验预览包并安装或更新外置能力定义。
     *
     * @param stagingToken 预检接口返回的临时安装凭证
     * @return 安装或更新后的能力定义
     * @throws BizException 预览失效、包被修改、同编码能力不可更新或文件落盘失败时抛出
     */
    public synchronized AgentCapabilityResponse install(String stagingToken) {
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
            PackageInspection inspection = validatePackage(stagedPackage);
            boolean updating = existingUpdatableCapability(inspection.definition().getCode()).isPresent();
            String packageHash = Files.readString(hashFile, StandardCharsets.UTF_8).trim();
            Path installRoot = installedRoot();
            Files.createDirectories(installRoot);
            target = installRoot.resolve(inspection.definition().getCode()).normalize();
            if (!target.startsWith(installRoot)) {
                throw packageExists(inspection.definition().getCode());
            }
            if (Files.exists(target)) {
                if (!updating) {
                    throw packageExists(inspection.definition().getCode());
                }
                backup = stagingDir.resolve("previous-package");
                moveDirectory(target, backup);
            }
            moveDirectory(stagedPackage, target);
            targetReplaced = true;
            ModuleDefinition installedDefinition = moduleDefinitionService.inspectCapabilityDirectory(target);
            AgentCapabilityResponse response = agentCapabilityService.installOrUpdatePackage(installedDefinition, packageHash);
            deleteDirectoryQuietly(stagingDir);
            log.info("Capability package {} completed code={} version={} path={}",
                    updating ? "update" : "installation", installedDefinition.getCode(), installedDefinition.getVersion(), target);
            return response;
        } catch (BizException e) {
            if (targetReplaced && target != null && Files.exists(target)) {
                deleteDirectoryQuietly(target);
            }
            restoreBackup(backup, target);
            throw e;
        } catch (Exception e) {
            if (targetReplaced && target != null && Files.exists(target)) {
                deleteDirectoryQuietly(target);
            }
            restoreBackup(backup, target);
            log.info("Install capability package failed token={} message={}", stagingToken, e.getMessage());
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    "安装能力失败：" + e.getMessage());
        }
    }

    private PackageInspection validatePackage(Path packageRoot) throws IOException {
        ModuleDefinition definition = moduleDefinitionService.inspectCapabilityDirectory(packageRoot);
        if (definition.getCode() == null || !definition.getCode().matches("[a-z0-9]+(?:-[a-z0-9]+)*")
                || definition.getCode().length() > 64) {
            throw packageError("SKILL.md name 必须是最多 64 位的小写连字符格式");
        }
        if (definition.getName() == null || definition.getName().isBlank()) {
            throw packageError("lingxi.json presentation.displayName 不能为空");
        }
        if (definition.getVersion() == null || !definition.getVersion().matches("[0-9A-Za-z][0-9A-Za-z._-]{0,39}")) {
            throw packageError("lingxi.json version 不能为空且只能包含字母、数字、点、下划线和短横线");
        }
        if (moduleDefinitionService.readPrompt(definition).isBlank()) {
            throw packageError("SKILL.md 工作流正文不能为空");
        }
        if (definition.getIcon() != null && !definition.getIcon().isBlank()) {
            requireRegularFile(packageRoot, definition.getIcon(), "能力图标文件不存在");
        }
        if (definition.getGuides() != null) {
            definition.getGuides().forEach(guide -> moduleDefinitionService.readGuide(definition, guide.getFile()));
        }
        LingxiCapabilityDefinition extension = moduleDefinitionService.readCapabilityExtension(packageRoot);
        Path scriptsDir = packageRoot.resolve(SCRIPTS_DIR).normalize();
        boolean runtimeIncluded = Files.isDirectory(scriptsDir);
        String entrypoint = extension.getEntrypoint() == null ? "" : extension.getEntrypoint().trim().replace('\\', '/');
        boolean hasCommands = extension.getCommands() != null && !extension.getCommands().isEmpty();
        if (hasCommands) {
            if (!runtimeIncluded || entrypoint.isBlank()) {
                throw packageError("可执行 Skill 必须在 lingxi.json 中声明 entrypoint");
            }
        }
        if (!entrypoint.isBlank()) {
            if (!entrypoint.startsWith(SCRIPTS_DIR + "/")) {
                throw packageError("entrypoint 必须是相对于 Skill 根目录的 scripts/ 路径");
            }
            if (!entrypoint.endsWith(".py")) {
                throw packageError("Lingxi 当前只支持 Python entrypoint，入口文件必须使用 .py 后缀");
            }
            requireRegularFile(packageRoot, entrypoint, "Skill entrypoint 不存在");
        }
        List<CapabilityCommandDefinition> commands = new java.util.ArrayList<>();
        Set<String> commandNames = new java.util.HashSet<>();
        for (CapabilityCommandExtensionDefinition command : extension.getCommands() == null
                ? List.<CapabilityCommandExtensionDefinition>of() : extension.getCommands()) {
            if (command == null || command.getCommand() == null || !command.getCommand().trim()
                    .matches("[a-z0-9]+(?:-[a-z0-9]+)* [a-z0-9]+(?:-[a-z0-9]+)*")) {
                throw packageError("commands.command 必须使用 group action 格式");
            }
            String publicCommand = command.getCommand().trim().replaceAll("\\s+", " ");
            if (!commandNames.add(publicCommand)) {
                throw packageError("commands.command 不能重复：" + publicCommand);
            }
            if (command.getDisplayName() == null || command.getDisplayName().isBlank()) {
                throw packageError("commands.displayName 动作短语不能为空");
            }
            for (CapabilityCommandOutputDefinition output : command.getOutputs() == null
                    ? List.<CapabilityCommandOutputDefinition>of() : command.getOutputs()) {
                if (output == null || output.getType() == null || output.getType().isBlank()
                        || output.getPathField() == null || output.getPathField().isBlank()) {
                    throw packageError("commands.outputs 必须包含 type 和 pathField");
                }
            }
            CapabilityCommandDefinition item = new CapabilityCommandDefinition();
            item.setModuleCode(definition.getCode());
            item.setCode(publicCommand.replace(' ', '.'));
            item.setName(command.getDisplayName().trim());
            item.setIcon(command.getIcon());
            item.setCommand(publicCommand);
            item.setDescription(command.getDescription());
            item.setOutputs(command.getOutputs() == null ? List.of() : command.getOutputs());
            commands.add(item);
        }
        return new PackageInspection(definition, commands, runtimeIncluded, readRequirements(scriptsDir));
    }

    private void extractArchive(Path archive, Path targetRoot) throws IOException {
        long extractedSize = 0;
        int entryCount = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ENTRY_COUNT) {
                    throw packageError("能力安装包文件数量不能超过 " + MAX_ENTRY_COUNT);
                }
                String entryName = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
                if (entryName.isBlank() || entryName.startsWith("/") || entryName.contains("\u0000")) {
                    throw packageError("能力安装包包含非法文件路径");
                }
                Path destination = targetRoot.resolve(entryName).normalize();
                if (!destination.startsWith(targetRoot)) {
                    throw packageError("能力安装包包含越界文件路径：" + entryName);
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
                            throw packageError("能力安装包解压后不能超过 50MB");
                        }
                        output.write(buffer, 0, count);
                    }
                }
            }
        }
        if (entryCount == 0) {
            throw packageError("能力安装包为空");
        }
        try (var paths = Files.walk(targetRoot)) {
            if (paths.anyMatch(Files::isSymbolicLink)) {
                throw packageError("能力安装包不能包含符号链接");
            }
        }
    }

    private Path locatePackageRoot(Path extractedRoot) throws IOException {
        if (Files.isRegularFile(extractedRoot.resolve(SKILL_FILE))) {
            return extractedRoot;
        }
        try (var paths = Files.list(extractedRoot)) {
            List<Path> candidates = paths
                    .filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .filter(path -> Files.isRegularFile(path.resolve(SKILL_FILE)))
                    .toList();
            if (candidates.size() != 1) {
                throw packageError("ZIP 根目录或唯一一级子目录中必须包含 SKILL.md");
            }
            return candidates.get(0);
        }
    }

    private Optional<ModuleDefinition> existingUpdatableCapability(String code) {
        if (moduleDefinitionService.listBuiltinCapabilities().stream()
                .anyMatch(item -> code.equals(item.getCode()))) {
            throw packageExists(code);
        }
        return moduleDefinitionService.listInstalledCapabilities().stream()
                .filter(item -> code.equals(item.getCode()))
                .findFirst();
    }

    private CapabilityPackageInspectionResponse inspectionResponse(String stagingToken, long packageSize,
                                                                   String packageHash, PackageInspection inspection,
                                                                   Optional<ModuleDefinition> existing) {
        ModuleDefinition definition = inspection.definition();
        CapabilityPackageInspectionResponse response = new CapabilityPackageInspectionResponse();
        response.setStagingToken(stagingToken);
        response.setCode(definition.getCode());
        response.setName(definition.getName());
        response.setVersion(definition.getVersion());
        response.setDescription(definition.getDescription());
        response.setUpdate(existing.isPresent());
        response.setCurrentVersion(existing.map(ModuleDefinition::getVersion).orElse(null));
        response.setPackageSize(packageSize);
        response.setPackageHash(packageHash);
        response.setRuntimeIncluded(inspection.runtimeIncluded());
        response.setConfigParameterCount(definition.getConfig() == null ? 0 : definition.getConfig().size());
        response.setParameterCount(definition.getParameters() == null ? 0 : definition.getParameters().size());
        response.setGuideCount(definition.getGuides() == null ? 0 : definition.getGuides().size());
        response.setRequirements(inspection.requirements());
        response.setCommands(inspection.commands().stream().map(command -> {
            CapabilityPackageCommandResponse item = new CapabilityPackageCommandResponse();
            item.setCode(command.getCode());
            item.setName(command.getName());
            item.setIcon(command.getIcon());
            item.setCommand(command.getCommand());
            item.setDescription(command.getDescription());
            return item;
        }).toList());
        return response;
    }

    private List<String> readRequirements(Path commandsDir) throws IOException {
        Path requirements = commandsDir.resolve("requirements.txt");
        if (!Files.isRegularFile(requirements)) {
            return List.of();
        }
        return Files.readAllLines(requirements, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .toList();
    }

    private void requireRegularFile(Path root, String relativePath, String message) {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path file = absoluteRoot.resolve(relativePath).normalize().toAbsolutePath().normalize();
        if (!file.startsWith(absoluteRoot) || !Files.isRegularFile(file)) {
            throw packageError(message + "：" + relativePath);
        }
    }

    private void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private void restoreBackup(Path backup, Path target) {
        if (backup == null || target == null || !Files.exists(backup)) {
            return;
        }
        try {
            moveDirectory(backup, target);
        } catch (IOException restoreError) {
            log.error("Restore previous capability package failed backup={} target={} message={}",
                    backup, target, restoreError.getMessage());
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
                } catch (IOException e) {
                    log.info("Clean capability staging failed path={} message={}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.info("Scan capability staging failed path={} message={}", root, e.getMessage());
        }
    }

    private Path installedRoot() {
        return Path.of(properties.getDefinitions().getInstalledSkillDir()).toAbsolutePath().normalize();
    }

    private Path stagingRoot() {
        return installedRoot().resolve(".staging").normalize();
    }

    private void deleteDirectoryQuietly(Path root) {
        try {
            deleteDirectory(root);
        } catch (IOException e) {
            log.info("Delete capability directory failed path={} message={}", root, e.getMessage());
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
        return new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.CAPABILITY_PACKAGE_INVALID, message);
    }

    private BizException packageExists(String code) {
        return new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.CAPABILITY_PACKAGE_EXISTS,
                "能力编码已存在：" + code);
    }

    private BizException stagingNotFound() {
        return new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.CAPABILITY_PACKAGE_STAGING_NOT_FOUND,
                "能力安装预览已失效，请重新上传");
    }

    private record PackageInspection(ModuleDefinition definition,
                                     List<CapabilityCommandDefinition> commands,
                                     boolean runtimeIncluded,
                                     List<String> requirements) {
    }
}
