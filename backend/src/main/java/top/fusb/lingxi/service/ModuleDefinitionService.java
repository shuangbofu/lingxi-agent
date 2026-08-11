package top.fusb.lingxi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import top.fusb.lingxi.config.LingxiProperties;
import top.fusb.lingxi.definition.AnalysisPremiseDefinition;
import top.fusb.lingxi.definition.CapabilityCommandExtensionDefinition;
import top.fusb.lingxi.definition.CapabilityPresentationDefinition;
import top.fusb.lingxi.definition.LingxiCapabilityDefinition;
import top.fusb.lingxi.definition.ModuleDefinition;
import top.fusb.lingxi.definition.ModuleGuideDefinition;
import top.fusb.lingxi.definition.ModuleParameterDefinition;
import top.fusb.lingxi.definition.ScenarioRecommendationDefinition;
import top.fusb.lingxi.dto.AgentDefinitionGuideRequest;
import top.fusb.lingxi.dto.AgentDefinitionParameterRequest;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.runtime.api.event.RuntimeActionIcon;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModuleDefinitionService {

    private static final String SCENARIO_MODULE_DIR = "scenarios";
    private static final String SKILL_MODULE_DIR = "skills";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final String SKILL_FILE = "SKILL.md";
    private static final String LINGXI_CAPABILITY_FILE = "lingxi.json";
    private static final String CAPABILITY_RUNTIME_DIR = "scripts";
    private static final String PREMISE_DEFINITION_FILE = "premises.json";
    private static final String SCENARIO_RECOMMENDATION_FILE = "scenario-recommendations.json";
    private static final String PLATFORM_DEFINITION_ROOT = "definitions/";
    private static final Pattern SKILL_FRONTMATTER_PATTERN = Pattern.compile(
            "\\A---\\R(?<frontmatter>.*?)\\R---(?:\\R|\\z)", Pattern.DOTALL);
    private static final Pattern SKILL_NAME_PATTERN = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Set<String> SKILL_FRONTMATTER_FIELDS = Set.of(
            "name", "description", "license", "allowed-tools", "metadata");
    private static final Set<String> SCENARIO_FORBIDDEN_FIELDS = Set.of(
            "runtimePath", "config", "recommendedScenarioCodes", "autoFollowUpScenarioCode");

    private final ObjectMapper objectMapper;
    private final LingxiProperties properties;

    public List<ModuleDefinition> listInstalledScenarios() {
        return readModuleDefinitions(installedScenarioRoot(), SCENARIO_MODULE_DIR,
                true, "读取已安装场景目录失败").stream()
                .sorted(Comparator.comparing(ModuleDefinition::getCode, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    public List<ModuleDefinition> listCapabilities() {
        Map<String, ModuleDefinition> definitions = new LinkedHashMap<>();
        for (ModuleDefinition definition : Stream.concat(listBuiltinCapabilities().stream(), listInstalledCapabilities().stream()).toList()) {
            ModuleDefinition existing = definitions.putIfAbsent(definition.getCode(), definition);
            if (existing != null) {
                throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                        "能力模块编码重复：" + definition.getCode());
            }
        }
        return definitions.values().stream()
                .sorted(Comparator.comparing(ModuleDefinition::getCode, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    /**
     * 读取定义目录中的分析情境初始数据。
     *
     * @return 按排序值排列的分析情境定义
     * @throws BizException 定义文件格式错误时抛出
     */
    public List<AnalysisPremiseDefinition> listPremises() {
        ClassPathResource resource = new ClassPathResource(PLATFORM_DEFINITION_ROOT + PREMISE_DEFINITION_FILE);
        if (!resource.exists()) {
            return List.of();
        }
        try (InputStream input = resource.getInputStream()) {
            List<AnalysisPremiseDefinition> definitions = objectMapper.readValue(input, new TypeReference<>() {});
            return definitions.stream()
                    .sorted(Comparator.comparing(AnalysisPremiseDefinition::getSortOrder,
                            Comparator.nullsLast(Integer::compareTo)))
                    .toList();
        } catch (Exception e) {
            log.info("读取分析情境定义失败 resource={} message={}", resource.getPath(), e.getMessage());
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    "读取分析情境定义失败: " + e.getMessage());
        }
    }

    /**
     * 读取平台维护的场景推荐关系，关系不属于任一场景模块。
     *
     * @return 场景推荐关系定义
     * @throws BizException 文件格式错误时抛出
     */
    public List<ScenarioRecommendationDefinition> listScenarioRecommendations() {
        ClassPathResource resource = new ClassPathResource(PLATFORM_DEFINITION_ROOT + SCENARIO_RECOMMENDATION_FILE);
        if (!resource.exists()) {
            return List.of();
        }
        try (InputStream input = resource.getInputStream()) {
            return objectMapper.readValue(input, new TypeReference<>() {});
        } catch (Exception e) {
            log.info("读取场景推荐关系失败 resource={} message={}", resource.getPath(), e.getMessage());
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    "读取场景推荐关系失败: " + e.getMessage());
        }
    }

    /**
     * 读取并校验平台维护的场景推荐关系，场景模块本身不保存或引用其他场景。
     *
     * @return 以来源场景编码为键的推荐目标集合
     * @throws BizException 关系引用未知场景、空编码或自身时抛出
     */
    public Map<String, Set<String>> scenarioRecommendationMap() {
        Set<String> scenarioCodes = listInstalledScenarios().stream()
                .map(ModuleDefinition::getCode)
                .filter(code -> code != null && !code.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (ScenarioRecommendationDefinition definition : listScenarioRecommendations()) {
            String source = normalizeCode(definition.getSource());
            if (source == null || !scenarioCodes.contains(source)) {
                throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                        "场景推荐关系引用了未知来源：" + source);
            }
            LinkedHashSet<String> targets = new LinkedHashSet<>();
            for (String value : definition.getTargets() == null ? Set.<String>of() : definition.getTargets()) {
                String target = normalizeCode(value);
                if (target == null || !scenarioCodes.contains(target)) {
                    throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                            "场景推荐关系引用了未知目标：" + target);
                }
                if (source.equals(target)) {
                    throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                            "场景推荐关系不能指向自身：" + source);
                }
                targets.add(target);
            }
            result.computeIfAbsent(source, ignored -> new LinkedHashSet<>()).addAll(targets);
        }
        return result;
    }

    /**
     * 查询平台为指定场景配置的推荐目标。
     *
     * @param sourceCode 来源场景编码
     * @return 与模块定义隔离的推荐目标集合
     */
    public Set<String> scenarioRecommendationCodes(String sourceCode) {
        String source = normalizeCode(sourceCode);
        return new LinkedHashSet<>(scenarioRecommendationMap().getOrDefault(source, Set.of()));
    }

    public List<ModuleDefinition> listBuiltinCapabilities() {
        Set<String> resourceSkillCodes = properties.getDefinitions().getResourceSkillCodes();
        return readCapabilityDefinitions(Path.of(properties.getDefinitions().getInstalledSkillDir()), false,
                "读取默认 Skill 失败").stream()
                .filter(definition -> resourceSkillCodes.contains(definition.getCode()))
                .toList();
    }

    public List<ModuleDefinition> listInstalledCapabilities() {
        Set<String> resourceSkillCodes = properties.getDefinitions().getResourceSkillCodes();
        return readCapabilityDefinitions(Path.of(properties.getDefinitions().getInstalledSkillDir()), true,
                "读取已安装 Skill 目录失败").stream()
                .filter(definition -> !resourceSkillCodes.contains(definition.getCode()))
                .toList();
    }

    /**
     * 读取并校验一个待安装能力目录的模块定义。
     *
     * @param moduleDir 已解压的能力包根目录
     * @return 带绝对模块目录信息的能力定义
     * @throws BizException SKILL.md、lingxi.json 缺失、格式错误或路径无效时抛出
     */
    public ModuleDefinition inspectCapabilityDirectory(Path moduleDir) {
        Path absoluteDir = moduleDir.toAbsolutePath().normalize();
        Path parent = absoluteDir.getParent();
        if (parent == null || !Files.isDirectory(absoluteDir)) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "能力包目录无效");
        }
        ModuleDefinition definition = readCapabilityDefinition(parent, absoluteDir, true, "读取能力包失败");
        if (definition == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                    "能力包缺少 SKILL.md 或 lingxi.json");
        }
        return definition;
    }

    /**
     * 读取并校验待安装场景目录。
     *
     * @param moduleDir 包含 manifest.json 的场景目录
     * @return 场景模块定义
     * @throws BizException 目录、manifest 或场景资源无效时抛出
     */
    public ModuleDefinition inspectScenarioDirectory(Path moduleDir) {
        Path absoluteDir = moduleDir.toAbsolutePath().normalize();
        Path parent = absoluteDir.getParent();
        if (parent == null || !Files.isDirectory(absoluteDir)) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "场景包目录无效");
        }
        ModuleDefinition definition = readModuleManifest(
                SCENARIO_MODULE_DIR, parent, absoluteDir, true, "读取场景包失败");
        if (definition == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                    "场景包缺少 manifest.json");
        }
        return definition;
    }

    public String readPrompt(ModuleDefinition definition) {
        if (definition != null && definition.getModulePath() != null
                && definition.getModulePath().startsWith(SKILL_MODULE_DIR + "/")) {
            return readAgentSkill(definition.getModuleDirectory()).instructions();
        }
        return readDefinitionFile(definition, definition.getPromptFile(), "读取定义提示词失败");
    }

    public String readGuide(ModuleDefinition definition, String fileName) {
        return readDefinitionFile(definition, fileName, "读取定义使用说明失败");
    }

    /**
     * 读取 Lingxi 对标准 Skill 的平台扩展定义。
     *
     * @param moduleDir Skill 根目录
     * @return Lingxi 平台扩展
     * @throws BizException 扩展文件缺失、格式错误或路径越界时抛出
     */
    public LingxiCapabilityDefinition readCapabilityExtension(Path moduleDir) {
        Path absoluteDir = moduleDir.toAbsolutePath().normalize();
        Path extensionFile = absoluteDir.resolve(LINGXI_CAPABILITY_FILE).normalize();
        if (!extensionFile.startsWith(absoluteDir) || !Files.isRegularFile(extensionFile)) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    "能力缺少 lingxi.json: " + absoluteDir.getFileName());
        }
        try {
            LingxiCapabilityDefinition extension = objectMapper.readValue(
                    stripBom(Files.readString(extensionFile, StandardCharsets.UTF_8)),
                    LingxiCapabilityDefinition.class);
            validateCommandIcons(extension, absoluteDir.getFileName().toString());
            return extension;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.info("读取 Lingxi 能力扩展失败 file={} type={}", extensionFile,
                    e.getClass().getSimpleName());
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    "lingxi.json 格式错误，请检查 JSON 语法和字段类型: " + absoluteDir.getFileName());
        }
    }

    /**
     * 查找包含指定入口文件的唯一能力运行时目录。
     *
     * @param entryFile 运行时入口文件名
     * @return 能力运行时绝对目录
     * @throws BizException 未找到运行时、存在多个候选或路径越界时抛出
     */
    public Path requireCapabilityRuntime(String entryFile) {
        List<Path> candidates = listCapabilities().stream()
                .filter(definition -> definition.getRuntimePath() != null && !definition.getRuntimePath().isBlank())
                .map(this::capabilityRuntimePath)
                .filter(path -> Files.isRegularFile(path.resolve(entryFile)))
                .toList();
        if (candidates.size() != 1) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    candidates.isEmpty() ? "未找到能力运行时入口: " + entryFile : "能力运行时入口不唯一: " + entryFile);
        }
        return candidates.get(0);
    }

    public List<AgentDefinitionGuideRequest> definitionGuideRequests(ModuleDefinition definition) {
        if (definition.getGuides() == null) {
            return List.of();
        }
        return java.util.stream.IntStream.range(0, definition.getGuides().size())
                .mapToObj(index -> {
                    AgentDefinitionGuideRequest request = definitionGuideRequest(definition, definition.getGuides().get(index));
                    request.setSortOrder(index * 10);
                    return request;
                })
                .toList();
    }

    public List<AgentDefinitionParameterRequest> definitionParameterRequests(List<ModuleParameterDefinition> definitions) {
        if (definitions == null) {
            return List.of();
        }
        return java.util.stream.IntStream.range(0, definitions.size())
                .mapToObj(index -> {
                    AgentDefinitionParameterRequest request = definitionParameterRequest(definitions.get(index));
                    request.setSortOrder(index * 10);
                    return request;
                })
                .toList();
    }

    private List<ModuleDefinition> readModuleDefinitions(Path moduleRoot, String moduleType, boolean installed, String message) {
        try {
            Path absoluteRoot = moduleRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(absoluteRoot)) {
                return List.of();
            }
            try (Stream<Path> paths = Files.list(absoluteRoot)) {
                return paths
                        .filter(Files::isDirectory)
                        .filter(path -> !path.getFileName().toString().startsWith("."))
                        .map(path -> readModuleManifest(moduleType, absoluteRoot, path, installed, message))
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparing(ModuleDefinition::getCode, Comparator.nullsLast(String::compareTo)))
                        .toList();
            }
        } catch (Exception e) {
            log.info("{} moduleType={} message={}", message, moduleType, e.getMessage());
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED, message + ": " + e.getMessage());
        }
    }

    private List<ModuleDefinition> readCapabilityDefinitions(Path moduleRoot, boolean installed, String message) {
        try {
            Path absoluteRoot = moduleRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(absoluteRoot)) {
                return List.of();
            }
            try (Stream<Path> paths = Files.list(absoluteRoot)) {
                return paths
                        .filter(Files::isDirectory)
                        .filter(path -> !path.getFileName().toString().startsWith("."))
                        .map(path -> readCapabilityDefinition(absoluteRoot, path, installed, message))
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparing(ModuleDefinition::getCode, Comparator.nullsLast(String::compareTo)))
                        .toList();
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.info("{} message={}", message, e.getMessage());
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    message + ": " + e.getMessage());
        }
    }

    private ModuleDefinition readCapabilityDefinition(Path moduleRoot, Path moduleDir,
                                                      boolean installed, String message) {
        Path skillFile = moduleDir.resolve(SKILL_FILE).normalize().toAbsolutePath().normalize();
        Path extensionFile = moduleDir.resolve(LINGXI_CAPABILITY_FILE).normalize().toAbsolutePath().normalize();
        if (!Files.isRegularFile(skillFile) && !Files.isRegularFile(extensionFile)) {
            return null;
        }
        if (!skillFile.startsWith(moduleRoot.toAbsolutePath().normalize())
                || !extensionFile.startsWith(moduleRoot.toAbsolutePath().normalize())) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED, message + ": 模块路径越界");
        }
        if (!Files.isRegularFile(skillFile) || !Files.isRegularFile(extensionFile)) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    message + ": 能力必须同时包含 SKILL.md 和 lingxi.json");
        }
        try {
            AgentSkill skill = readAgentSkill(moduleDir);
            LingxiCapabilityDefinition extension = readCapabilityExtension(moduleDir);
            String directoryName = moduleDir.getFileName().toString();
            validateAgentSkill(skill, directoryName);
            validateCapabilityExtension(extension, directoryName);

            CapabilityPresentationDefinition presentation = extension.getPresentation();
            ModuleDefinition definition = new ModuleDefinition();
            definition.setCode(skill.name());
            definition.setDescription(skill.description());
            definition.setVersion(extension.getVersion());
            definition.setName(presentation.getDisplayName());
            definition.setIcon(presentation.getIcon());
            definition.setEnabled(extension.getEnabledByDefault());
            definition.setParameters(extension.getTaskParameters());
            definition.setConfig(extension.getConfigurationParameters());
            definition.setGuides(extension.getGuides());
            definition.setPromptFile(SKILL_FILE);
            if (Files.isDirectory(moduleDir.resolve(CAPABILITY_RUNTIME_DIR))) {
                definition.setRuntimePath(CAPABILITY_RUNTIME_DIR);
            }
            definition.setModulePath(SKILL_MODULE_DIR + "/" + directoryName);
            definition.setModuleDirectory(moduleDir.toAbsolutePath().normalize());
            definition.setInstalled(installed);
            return definition;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.info("{} skill={} message={}", message, skillFile, e.getMessage());
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    message + ": " + e.getMessage());
        }
    }

    private ModuleDefinition readModuleManifest(String moduleType, Path moduleRoot, Path moduleDir,
                                                boolean installed, String message) {
        Path manifest = moduleDir.resolve(MANIFEST_FILE).normalize();
        if (!Files.isRegularFile(manifest)) {
            return null;
        }
        Path absoluteManifest = manifest.toAbsolutePath().normalize();
        if (!absoluteManifest.startsWith(moduleRoot)) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED, message + ": 模块路径越界");
        }
        try {
            String manifestText = stripBom(Files.readString(absoluteManifest, StandardCharsets.UTF_8));
            JsonNode manifestNode = objectMapper.readTree(manifestText);
            ModuleDefinition definition = objectMapper.readValue(manifestText, ModuleDefinition.class);
            validateModuleBoundary(moduleType, manifestNode, definition);
            definition.setModulePath(moduleType + "/" + moduleDir.getFileName());
            definition.setModuleDirectory(moduleDir.toAbsolutePath().normalize());
            definition.setInstalled(installed);
            return definition;
        } catch (Exception e) {
            log.info("{} manifest={} message={}", message, manifest, e.getMessage());
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED, message + ": " + e.getMessage());
        }
    }

    private AgentDefinitionGuideRequest definitionGuideRequest(ModuleDefinition owner, ModuleGuideDefinition definition) {
        AgentDefinitionGuideRequest request = new AgentDefinitionGuideRequest();
        request.setKey(definition.getKey());
        request.setTitle(definition.getTitle());
        request.setDescription(definition.getDescription());
        request.setContent(readGuide(owner, definition.getFile()));
        request.setSortOrder(definition.getSortOrder());
        return request;
    }

    private void validateModuleBoundary(String moduleType, JsonNode manifest, ModuleDefinition definition) {
        Set<String> forbiddenFields = SCENARIO_FORBIDDEN_FIELDS;
        List<String> presentFields = forbiddenFields.stream().filter(manifest::has).sorted().toList();
        if (!presentFields.isEmpty()) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    "场景模块不能声明其他场景：" + definition.getCode()
                            + " fields=" + String.join(",", presentFields));
        }
    }

    private AgentSkill readAgentSkill(Path moduleDir) {
        Path skillFile = moduleDir.toAbsolutePath().normalize().resolve(SKILL_FILE).normalize();
        try {
            String content = stripBom(Files.readString(skillFile, StandardCharsets.UTF_8));
            Matcher matcher = SKILL_FRONTMATTER_PATTERN.matcher(content);
            if (!matcher.find()) {
                throw new IOException("SKILL.md 缺少合法 YAML frontmatter");
            }
            LoaderOptions options = new LoaderOptions();
            Object value = new Yaml(new SafeConstructor(options)).load(matcher.group("frontmatter"));
            if (!(value instanceof Map<?, ?> frontmatter)) {
                throw new IOException("SKILL.md frontmatter 必须是对象");
            }
            List<String> unexpectedFields = frontmatter.keySet().stream()
                    .map(String::valueOf)
                    .filter(key -> !SKILL_FRONTMATTER_FIELDS.contains(key))
                    .sorted()
                    .toList();
            if (!unexpectedFields.isEmpty()) {
                throw new IOException("SKILL.md frontmatter 包含非标准字段: " + String.join(",", unexpectedFields));
            }
            String name = stringValue(frontmatter.get("name"));
            String description = stringValue(frontmatter.get("description"));
            return new AgentSkill(name, description, content.substring(matcher.end()).strip());
        } catch (Exception e) {
            log.info("读取 Agent Skill 失败 file={} message={}", skillFile, e.getMessage());
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    "读取 Agent Skill 失败: " + e.getMessage());
        }
    }

    private void validateAgentSkill(AgentSkill skill, String directoryName) {
        if (skill.name() == null || !SKILL_NAME_PATTERN.matcher(skill.name()).matches()
                || skill.name().length() > 64) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    "Skill name 必须是最多 64 位的小写连字符格式: " + directoryName);
        }
        if (!skill.name().equals(directoryName)) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    "Skill name 必须与目录名一致: " + directoryName);
        }
        if (skill.description() == null || skill.description().isBlank()
                || skill.description().length() > 1024
                || skill.description().contains("<") || skill.description().contains(">")) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    "Skill description 不能为空、不能超过 1024 字符或包含尖括号: " + directoryName);
        }
        if (skill.instructions().isBlank()) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    "SKILL.md 工作流正文不能为空: " + directoryName);
        }
    }

    private void validateCapabilityExtension(LingxiCapabilityDefinition extension, String directoryName) {
        if (extension.getSchemaVersion() == null || extension.getSchemaVersion() != 1) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    "lingxi.json schemaVersion 当前必须为 1: " + directoryName);
        }
        if (extension.getVersion() == null || extension.getVersion().isBlank()) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    "lingxi.json version 不能为空: " + directoryName);
        }
        if (extension.getPresentation() == null
                || extension.getPresentation().getDisplayName() == null
                || extension.getPresentation().getDisplayName().isBlank()) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    "lingxi.json presentation.displayName 不能为空: " + directoryName);
        }
        boolean declaresDynamicOptionSource = Stream.concat(
                        extension.getTaskParameters() == null
                                ? Stream.<ModuleParameterDefinition>empty() : extension.getTaskParameters().stream(),
                        extension.getConfigurationParameters() == null
                                ? Stream.<ModuleParameterDefinition>empty() : extension.getConfigurationParameters().stream())
                .anyMatch(parameter -> parameter.getOptionSource() != null);
        if (declaresDynamicOptionSource) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    "lingxi.json 能力参数不能声明动态选项源: " + directoryName);
        }
    }

    /**
     * 校验外部能力定义使用的图标标识，避免 JSON 解析层直接绑定运行时枚举。
     *
     * @param extension Lingxi 能力扩展定义
     * @param directoryName 能力目录名，用于定位错误来源
     * @return 无返回值
     * @throws BizException 命令图标不在协议支持范围内时抛出
     */
    private void validateCommandIcons(LingxiCapabilityDefinition extension, String directoryName) {
        if (extension == null || extension.getCommands() == null) {
            return;
        }
        for (int index = 0; index < extension.getCommands().size(); index++) {
            CapabilityCommandExtensionDefinition command = extension.getCommands().get(index);
            if (command == null || command.getIcon() == null || command.getIcon().isBlank()) {
                continue;
            }
            try {
                RuntimeActionIcon.fromExternalValue(command.getIcon());
            } catch (IllegalArgumentException e) {
                throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                        "lingxi.json commands[" + index + "].icon 不支持：" + command.getIcon().trim()
                                + ": " + directoryName);
            }
        }
    }

    private String stringValue(Object value) {
        return value instanceof String text ? text.trim() : null;
    }

    private record AgentSkill(String name, String description, String instructions) {
    }

    private AgentDefinitionParameterRequest definitionParameterRequest(ModuleParameterDefinition definition) {
        AgentDefinitionParameterRequest request = new AgentDefinitionParameterRequest();
        request.setKey(definition.getKey());
        request.setName(definition.getName());
        request.setType(definition.getType());
        request.setRequired(Boolean.TRUE.equals(definition.getRequired()));
        request.setDescription(definition.getDescription());
        request.setOptions(definition.getOptions());
        request.setDefaultValue(definition.getDefaultValue());
        request.setVisible(definition.getVisible());
        request.setSortOrder(definition.getSortOrder());
        return request;
    }

    private String normalizeCode(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String readDefinitionFile(ModuleDefinition definition, String relativePath, String message) {
        if (definition == null || definition.getModuleDirectory() == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED, message + ": 定义模块路径缺失");
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED, message + ": 定义文件路径缺失");
        }
        try {
            Path moduleAbsolute = definition.getModuleDirectory().toAbsolutePath().normalize();
            Path fileAbsolute = moduleAbsolute.resolve(relativePath).normalize().toAbsolutePath().normalize();
            if (!fileAbsolute.startsWith(moduleAbsolute)) {
                throw new IOException("定义文件路径越界: " + relativePath);
            }
            return stripBom(Files.readString(fileAbsolute, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.info("{} module={} path={} message={}", message, definition.getCode(), relativePath, e.getMessage());
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED, message + ": " + e.getMessage());
        }
    }

    private Path capabilityRuntimePath(ModuleDefinition definition) {
        Path moduleDir = definition.getModuleDirectory().toAbsolutePath().normalize();
        Path runtimePath = moduleDir.resolve(definition.getRuntimePath()).toAbsolutePath().normalize();
        if (!runtimePath.startsWith(moduleDir) || !Files.isDirectory(runtimePath)) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    "能力运行时目录无效: " + definition.getCode());
        }
        return runtimePath;
    }

    private Path installedScenarioRoot() {
        return Path.of(properties.getDefinitions().getInstalledScenarioDir()).toAbsolutePath().normalize();
    }

    private String stripBom(String text) {
        if (text == null || text.isEmpty() || text.charAt(0) != '\uFEFF') {
            return text;
        }
        return text.substring(1);
    }
}
