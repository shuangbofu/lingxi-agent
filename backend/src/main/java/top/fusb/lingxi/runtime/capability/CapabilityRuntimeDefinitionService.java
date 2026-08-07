package top.fusb.lingxi.runtime.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.config.LingxiProperties;
import top.fusb.lingxi.definition.CapabilityRuntimeModuleDefinition;
import top.fusb.lingxi.definition.LingxiCapabilityDefinition;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class CapabilityRuntimeDefinitionService {

    private static final String SKILL_MODULE_DIR = "skills";
    private static final String SCRIPTS_DIR = "scripts";
    private static final String SKILL_FILE = "SKILL.md";
    private static final String LINGXI_CAPABILITY_FILE = "lingxi.json";

    private final ObjectMapper objectMapper;
    private final LingxiProperties properties;

    /**
     * 读取带 scripts 目录的标准 Skill，Lingxi 只补充可选的结果绑定。
     *
     * @return 当前安装的可执行 Skill
     * @throws BizException Skill 路径或 Lingxi 扩展无效时抛出
     */
    public List<CapabilityRuntimeModuleDefinition> modules() {
        Map<String, CapabilityRuntimeModuleDefinition> modules = new LinkedHashMap<>();
        for (CapabilityRuntimeModuleDefinition module : readModules(
                Path.of(properties.getDefinitions().getInstalledSkillDir()), "读取 Skill 失败")) {
            CapabilityRuntimeModuleDefinition existing = modules.putIfAbsent(module.getModuleCode(), module);
            if (existing != null) {
                throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                        "Skill 编码重复：" + module.getModuleCode());
            }
        }
        return modules.values().stream()
                .sorted(Comparator.comparing(CapabilityRuntimeModuleDefinition::getModuleCode))
                .toList();
    }

    private List<CapabilityRuntimeModuleDefinition> readModules(Path root, String message) {
        Path capabilityRoot = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(capabilityRoot)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(capabilityRoot)) {
            return paths
                    .filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .map(moduleDir -> readModule(capabilityRoot, moduleDir))
                    .filter(Objects::nonNull)
                    .toList();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.info("{} message={}", message, e.getMessage());
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    message + "：" + e.getMessage());
        }
    }

    private CapabilityRuntimeModuleDefinition readModule(Path capabilityRoot, Path moduleDir) {
        Path skillFile = moduleDir.resolve(SKILL_FILE).toAbsolutePath().normalize();
        Path extensionFile = moduleDir.resolve(LINGXI_CAPABILITY_FILE).toAbsolutePath().normalize();
        Path scriptsDir = moduleDir.resolve(SCRIPTS_DIR).toAbsolutePath().normalize();
        if (!Files.isRegularFile(skillFile) || !Files.isRegularFile(extensionFile)) {
            return null;
        }
        if (!skillFile.startsWith(capabilityRoot) || !extensionFile.startsWith(capabilityRoot)
                || !scriptsDir.startsWith(capabilityRoot)) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    "Skill 路径超出定义目录");
        }
        try {
            LingxiCapabilityDefinition extension = objectMapper.readValue(
                    stripBom(Files.readString(extensionFile, StandardCharsets.UTF_8)),
                    LingxiCapabilityDefinition.class);
            String entrypoint = extension.getEntrypoint();
            if (entrypoint != null && !entrypoint.isBlank()
                    && !entrypoint.trim().replace('\\', '/').endsWith(".py")) {
                throw new IllegalArgumentException("Lingxi 当前只支持 Python entrypoint: " + entrypoint);
            }
            String moduleCode = moduleDir.getFileName().toString();
            CapabilityRuntimeModuleDefinition module = new CapabilityRuntimeModuleDefinition();
            module.setModuleCode(moduleCode);
            module.setModulePath(SKILL_MODULE_DIR + "/" + moduleCode);
            module.setModuleDirectory(moduleDir);
            module.setDisplayName(extension.getPresentation().getDisplayName());
            module.setEntrypoint(entrypoint);
            module.setCommands(extension.getCommands());
            return module;
        } catch (Exception e) {
            log.info("读取 Skill 执行目录失败 extension={} message={}", extensionFile, e.getMessage());
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    "读取 Skill 执行目录失败：" + e.getMessage());
        }
    }

    private String stripBom(String text) {
        if (text == null || text.isEmpty() || text.charAt(0) != '\uFEFF') {
            return text;
        }
        return text.substring(1);
    }
}
