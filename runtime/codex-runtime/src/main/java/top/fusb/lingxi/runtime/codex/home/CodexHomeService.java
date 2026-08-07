package top.fusb.lingxi.runtime.codex.home;

import top.fusb.lingxi.runtime.api.model.RuntimeModelConfig;
import top.fusb.lingxi.runtime.api.model.RuntimeModelProtocol;
import top.fusb.lingxi.runtime.api.execution.RuntimeCommandGuideDescriptor;
import top.fusb.lingxi.runtime.api.execution.RuntimeSkillDescriptor;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpServerConfig;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpTransport;
import top.fusb.lingxi.runtime.api.support.RuntimeLocaleResolver;
import top.fusb.lingxi.runtime.codex.config.CodexSkillAccessMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@Slf4j
public class CodexHomeService {

    public static final String PROVIDER_NAME = "workbench";
    public static final String DEEPSEEK_PROVIDER_NAME = "deepseek";
    public static final String DEEPSEEK_PROVIDER_TYPE = "DEEPSEEK";
    public static final String DEEPSEEK_V4_FLASH_MODEL = "deepseek-v4-flash";
    private static final String DEEPSEEK_MODEL_CATALOG_RESOURCE = "/codex/deepseek-v4-flash-models.json";
    private static final String MCP_PAGINATION_PROXY_RESOURCE = "/codex/mcp-pagination-proxy.py";
    private static final String COMMAND_CATALOG_DIR = "command-catalog";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String processLocale;

    public CodexHomeService() {
        this(null);
    }

    /**
     * 创建 Codex 会话目录服务并解析当前机器真实可用的命令 locale。
     *
     * @param preferredLocale 配置中声明的优先 locale
     */
    public CodexHomeService(String preferredLocale) {
        this.processLocale = RuntimeLocaleResolver.resolveProcessLocale(preferredLocale).orElse("C");
    }

    /**
     * 返回当前机器已安装且用于 Codex 及其子命令的 locale。
     *
     * @return 已解析的进程 locale
     */
    public String processLocale() {
        return processLocale;
    }

    /**
     * 为单次任务生成隔离的执行引擎配置目录。
     *
     * @param taskId 任务 ID
     * @param conversationRootTaskId 会话根任务 ID，用于多轮任务复用同一个 CODEX_HOME
     * @param modelConfig 当前任务最终使用的模型配置和 API Key
     * @return CODEX_HOME 目录
     * @throws IllegalStateException API Key、baseUrl 或模型未配置，或配置文件写入失败时抛出
     */
    public Path prepare(Long taskId, Long conversationRootTaskId, RuntimeModelConfig modelConfig) {
        return prepare(taskId, conversationRootTaskId, modelConfig, List.of(), List.of());
    }

    public Path prepare(Long taskId, Long conversationRootTaskId, RuntimeModelConfig modelConfig,
                        List<RuntimeMcpServerConfig> mcpServers) {
        return prepare(taskId, conversationRootTaskId, modelConfig, mcpServers, List.of());
    }

    public Path prepare(Long taskId, Long conversationRootTaskId, RuntimeModelConfig modelConfig,
                        List<RuntimeMcpServerConfig> mcpServers, List<RuntimeSkillDescriptor> skills) {
        return prepare(taskId, conversationRootTaskId, modelConfig, mcpServers, skills,
                CodexSkillAccessMode.NATIVE_SKILL);
    }

    /**
     * 按指定能力访问模式生成单次任务使用的隔离 CODEX_HOME。
     *
     * @param taskId 任务 ID
     * @param conversationRootTaskId 会话根任务 ID
     * @param modelConfig 当前模型配置
     * @param mcpServers 当前任务挂载的 MCP 服务
     * @param skills 当前任务授权的标准 Skill
     * @param skillAccessMode Codex 读取能力说明的模式
     * @return 已完成配置和能力投影的 CODEX_HOME
     * @throws IllegalStateException 配置缺失、能力投影或文件写入失败时抛出
     */
    public Path prepare(Long taskId, Long conversationRootTaskId, RuntimeModelConfig modelConfig,
                        List<RuntimeMcpServerConfig> mcpServers, List<RuntimeSkillDescriptor> skills,
                        CodexSkillAccessMode skillAccessMode) {
        return prepare(taskId, conversationRootTaskId, modelConfig, mcpServers, skills, List.of(), skillAccessMode);
    }

    /**
     * 按指定能力访问模式生成单次任务使用的隔离 CODEX_HOME。
     *
     * @param taskId 任务 ID
     * @param conversationRootTaskId 会话根任务 ID
     * @param modelConfig 当前模型配置
     * @param mcpServers 当前任务挂载的 MCP 服务
     * @param skills 当前任务授权的标准 Skill
     * @param commandGuides Runtime 提供的平台命令说明
     * @param skillAccessMode Codex 读取能力说明的模式
     * @return 已完成配置和能力投影的 CODEX_HOME
     * @throws IllegalStateException 配置缺失、能力投影或文件写入失败时抛出
     */
    public Path prepare(Long taskId, Long conversationRootTaskId, RuntimeModelConfig modelConfig,
                        List<RuntimeMcpServerConfig> mcpServers, List<RuntimeSkillDescriptor> skills,
                        List<RuntimeCommandGuideDescriptor> commandGuides,
                        CodexSkillAccessMode skillAccessMode) {
        String apiKey = blankToNull(modelConfig.apiKey());
        String baseUrl = blankToNull(modelConfig.baseUrl());
        String model = blankToNull(modelConfig.model());
        if (apiKey == null) {
            throw new IllegalStateException("执行引擎 API Key 未配置");
        }
        if (baseUrl == null) {
            throw new IllegalStateException("执行引擎 Base URL 未配置");
        }
        if (model == null) {
            throw new IllegalStateException("执行引擎模型未配置");
        }
        if (modelConfig.protocol() != null && modelConfig.protocol() != RuntimeModelProtocol.RESPONSES) {
            throw new IllegalStateException("Codex 仅支持 Responses 协议模型");
        }
        try {
            Path home = conversationHome(conversationRootTaskId == null ? taskId : conversationRootTaskId);
            Files.createDirectories(home);
            trySetDirectoryOwnerOnly(home);
            if (skillAccessMode == CodexSkillAccessMode.COMMAND_CATALOG) {
                installCommandCatalog(home, skills, commandGuides);
                deleteDirectory(home.resolve("skills"));
            } else {
                installSkills(home, skills);
                installPlatformCommandCatalog(home, commandGuides);
            }
            Path configPath = home.resolve("config.toml");
            Path authPath = home.resolve("auth.json");
            Path modelCatalogPath = home.resolve("models.json");
            Path mcpPaginationProxy = installMcpPaginationProxy(home, mcpServers);
            validateCodexModel(modelConfig);
            Files.writeString(configPath, configToml(modelConfig, modelCatalogPath, mcpServers, mcpPaginationProxy),
                    StandardCharsets.UTF_8);
            if (isDeepSeek(modelConfig)) {
                try (var input = CodexHomeService.class.getResourceAsStream(DEEPSEEK_MODEL_CATALOG_RESOURCE)) {
                    if (input == null) {
                        throw new IllegalStateException("DeepSeek Codex 模型目录资源缺失");
                    }
                    Files.copy(input, modelCatalogPath, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.deleteIfExists(authPath);
                trySetFileOwnerOnly(modelCatalogPath);
            } else {
                Files.writeString(authPath, authJson(apiKey), StandardCharsets.UTF_8);
                Files.deleteIfExists(modelCatalogPath);
                trySetFileOwnerOnly(authPath);
            }
            trySetFileOwnerOnly(configPath);
            log.info("写入隔离执行引擎配置 taskId={} codexHome={} provider={} baseUrl={} model={} reasoningEffort={} locale={}",
                    taskId, home, providerName(modelConfig), codexBaseUrl(modelConfig), model,
                    blankToNull(modelConfig.reasoningEffort()), processLocale);
            return home;
        } catch (Exception e) {
            throw new IllegalStateException("写入执行引擎配置失败：" + e.getMessage(), e);
        }
    }

    /**
     * 将标准 Skill 的模型说明投影为 Codex Runtime 私有的命令说明目录。
     *
     * @param home 当前会话的隔离 CODEX_HOME
     * @param skills 当前任务已授权的标准 Skill
     * @return 无返回值
     * @throws Exception Skill 来源无效或投影失败时抛出
     */
    private void installCommandCatalog(Path home, List<RuntimeSkillDescriptor> skills,
                                       List<RuntimeCommandGuideDescriptor> commandGuides) throws Exception {
        Path catalogRoot = home.resolve(COMMAND_CATALOG_DIR).toAbsolutePath().normalize();
        deleteDirectory(catalogRoot);
        Files.createDirectories(catalogRoot);
        for (RuntimeSkillDescriptor skill : skills == null ? List.<RuntimeSkillDescriptor>of() : skills) {
            if (skill == null || skill.name() == null
                    || !skill.name().matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
                throw new IllegalStateException("Skill name 无效");
            }
            Path source = Path.of(skill.sourcePath()).toAbsolutePath().normalize().toRealPath();
            Path skillFile = source.resolve("SKILL.md").normalize();
            if (!skillFile.startsWith(source) || !Files.isRegularFile(skillFile)) {
                throw new IllegalStateException("Skill 缺少 SKILL.md：" + skill.name());
            }
            Path target = catalogRoot.resolve(skill.name()).normalize();
            if (!target.startsWith(catalogRoot)) {
                throw new IllegalStateException("命令说明路径越界：" + skill.name());
            }
            Files.createDirectories(target);
            Files.copy(skillFile, target.resolve("guide.md"), StandardCopyOption.REPLACE_EXISTING);
            Path references = source.resolve("references").normalize();
            if (Files.isDirectory(references)) {
                copyDirectory(references, target.resolve("references"));
            }
        }
        writeCommandGuides(catalogRoot, commandGuides);
    }

    /**
     * 在原生 Skill 模式下单独安装平台命令说明，避免其生命周期被 Skill 模式控制。
     *
     * @param home 当前会话的隔离 CODEX_HOME
     * @param commandGuides 当前任务可用的平台命令说明
     * @return 无返回值
     * @throws Exception 目录重建或说明写入失败时抛出
     */
    private void installPlatformCommandCatalog(Path home,
                                               List<RuntimeCommandGuideDescriptor> commandGuides) throws Exception {
        Path catalogRoot = home.resolve(COMMAND_CATALOG_DIR).toAbsolutePath().normalize();
        deleteDirectory(catalogRoot);
        if (commandGuides == null || commandGuides.isEmpty()) {
            return;
        }
        Files.createDirectories(catalogRoot);
        writeCommandGuides(catalogRoot, commandGuides);
    }

    /**
     * 将平台命令说明写入已经准备好的命令目录，并阻止与 Skill 说明重名。
     *
     * @param catalogRoot 当前会话的命令说明根目录
     * @param commandGuides 当前任务可用的平台命令说明
     * @return 无返回值
     * @throws Exception 说明编码非法、目录冲突或文件写入失败时抛出
     */
    private void writeCommandGuides(Path catalogRoot,
                                    List<RuntimeCommandGuideDescriptor> commandGuides) throws Exception {
        for (RuntimeCommandGuideDescriptor guide
                : commandGuides == null ? List.<RuntimeCommandGuideDescriptor>of() : commandGuides) {
            if (guide == null || guide.code() == null
                    || !guide.code().matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
                throw new IllegalStateException("平台命令说明 code 无效");
            }
            Path target = catalogRoot.resolve(guide.code()).normalize();
            if (!target.startsWith(catalogRoot) || Files.exists(target)) {
                throw new IllegalStateException("平台命令说明与能力说明冲突：" + guide.code());
            }
            Files.createDirectories(target);
            Files.writeString(target.resolve("guide.md"), guide.content() == null ? "" : guide.content(),
                    StandardCharsets.UTF_8);
        }
    }

    /**
     * 返回模型可见的命令说明定位符，隐藏宿主机上的 CODEX_HOME 绝对路径。
     *
     * @param skillName 当前任务已授权的 Skill 名称
     * @return 可直接由 shell 展开的命令说明路径
     * @throws IllegalArgumentException Skill 名称不符合标准命名格式时抛出
     */
    public String commandGuideLocator(String skillName) {
        if (skillName == null || !skillName.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("命令说明参数无效");
        }
        return "$CODEX_HOME/" + COMMAND_CATALOG_DIR + "/" + skillName + "/guide.md";
    }

    /**
     * 将任务已授权 Skill 的模型说明投影到隔离 CODEX_HOME，不复制脚本和平台扩展。
     *
     * @param home 当前会话的隔离 CODEX_HOME
     * @param skills 当前任务已授权的标准 Skill
     * @return 无返回值
     * @throws Exception Skill 来源无效或投影失败时抛出
     */
    private void installSkills(Path home, List<RuntimeSkillDescriptor> skills) throws Exception {
        Path skillsRoot = home.resolve("skills").toAbsolutePath().normalize();
        deleteDirectory(skillsRoot);
        Files.createDirectories(skillsRoot);
        for (RuntimeSkillDescriptor skill : skills == null ? List.<RuntimeSkillDescriptor>of() : skills) {
            if (skill == null || skill.name() == null
                    || !skill.name().matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
                throw new IllegalStateException("Skill name 无效");
            }
            Path source = Path.of(skill.sourcePath()).toAbsolutePath().normalize().toRealPath();
            Path skillFile = source.resolve("SKILL.md").normalize();
            if (!skillFile.startsWith(source) || !Files.isRegularFile(skillFile)) {
                throw new IllegalStateException("Skill 缺少 SKILL.md：" + skill.name());
            }
            Path target = skillsRoot.resolve(skill.name()).normalize();
            if (!target.startsWith(skillsRoot)) {
                throw new IllegalStateException("Skill 投影路径越界：" + skill.name());
            }
            Files.createDirectories(target);
            Files.copy(skillFile, target.resolve("SKILL.md"), StandardCopyOption.REPLACE_EXISTING);
            Path references = source.resolve("references").normalize();
            if (Files.isDirectory(references)) {
                copyDirectory(references, target.resolve("references"));
            }
        }
    }

    private void copyDirectory(Path source, Path target) throws Exception {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative).normalize();
                if (!destination.startsWith(target) || Files.isSymbolicLink(path)) {
                    throw new IllegalStateException("Skill references 包含非法路径");
                }
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(path)) {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteDirectory(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * 返回单个任务隔离的 CODEX_HOME 路径。
     *
     * @param taskId 任务 ID
     * @return 任务对应的 CODEX_HOME 路径
     */
    public Path taskHome(Long taskId) {
        return Path.of("data", "codex-home", "task-" + taskId).toAbsolutePath().normalize();
    }

    public Path conversationHome(Long conversationRootTaskId) {
        return Path.of("data", "codex-home", "conversation-" + conversationRootTaskId).toAbsolutePath().normalize();
    }

    public Optional<CodexSessionRef> latestSession(Path codexHome) {
        Path sessionsDir = codexHome.resolve("sessions");
        if (!Files.exists(sessionsDir)) {
            return Optional.empty();
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(sessionsDir)) {
            return stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jsonl"))
                    .max(Comparator.comparing(this::lastModifiedMillis))
                    .flatMap(this::sessionRef);
        } catch (Exception e) {
            log.info("读取 Codex session 失败 codexHome={} message={}", codexHome, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<CodexSessionRef> sessionRef(Path path) {
        try {
            String firstLine = Files.lines(path, StandardCharsets.UTF_8).findFirst().orElse("");
            JsonNode root = objectMapper.readTree(firstLine);
            JsonNode payload = root.path("payload");
            String sessionId = blankToNull(payload.path("session_id").asText(null));
            if (sessionId == null) {
                sessionId = blankToNull(payload.path("id").asText(null));
            }
            if (sessionId == null) {
                return Optional.empty();
            }
            return Optional.of(new CodexSessionRef(sessionId, path.toAbsolutePath().normalize().toString()));
        } catch (Exception e) {
            log.info("解析 Codex session 失败 path={} message={}", path, e.getMessage());
            return Optional.empty();
        }
    }

    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 安装 Codex stdio MCP 的通用分页适配器，聚合服务端 tools/list 的全部分页。
     *
     * @param home 当前会话的隔离 CODEX_HOME
     * @param mcpServers 当前任务绑定的 MCP 服务
     * @return 存在 stdio MCP 时返回适配器路径，否则返回 null
     * @throws Exception 适配器资源缺失或复制失败时抛出
     */
    private Path installMcpPaginationProxy(Path home, List<RuntimeMcpServerConfig> mcpServers) throws Exception {
        boolean required = mcpServers != null && mcpServers.stream()
                .anyMatch(server -> server.transport() == RuntimeMcpTransport.STDIO);
        if (!required) {
            return null;
        }
        Path runtimeDir = home.resolve("runtime").toAbsolutePath().normalize();
        Files.createDirectories(runtimeDir);
        Path target = runtimeDir.resolve("mcp-pagination-proxy.py");
        try (var input = CodexHomeService.class.getResourceAsStream(MCP_PAGINATION_PROXY_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Codex MCP 分页适配器资源缺失");
            }
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        trySetFileOwnerOnly(target);
        return target;
    }

    private String configToml(RuntimeModelConfig modelConfig, Path modelCatalogPath,
                              List<RuntimeMcpServerConfig> mcpServers, Path mcpPaginationProxy) {
        String model = modelConfig.model().trim();
        String reasoningEffort = blankToNull(modelConfig.reasoningEffort());
        Integer contextWindowTokens = modelConfig.contextWindowTokens();
        String providerName = providerName(modelConfig);
        String reasoningConfig = reasoningEffort == null ? "" : "model_reasoning_effort = \"" + escapeToml(reasoningEffort) + "\"" + System.lineSeparator();
        String contextWindowConfig = isDeepSeek(modelConfig) || contextWindowTokens == null || contextWindowTokens <= 0
                ? "" : "model_context_window = " + contextWindowTokens + System.lineSeparator();
        String authConfig = isDeepSeek(modelConfig)
                ? "preferred_auth_method = \"apikey\"" + System.lineSeparator()
                + "forced_login_method = \"api\"" + System.lineSeparator()
                + "model_catalog_json = \"" + escapeToml(modelCatalogPath.toAbsolutePath().normalize().toString()) + "\""
                + System.lineSeparator()
                : "";
        String providerAuthConfig = isDeepSeek(modelConfig)
                ? "experimental_bearer_token = \"" + escapeToml(modelConfig.apiKey().trim()) + "\""
                : "requires_openai_auth = true";
        String modelToml = """
                model_provider = "%s"
                model = "%s"
                %s%s%sdisable_response_storage = true

                [shell_environment_policy.set]
                LANG = "%s"
                LC_ALL = "%s"
                LC_CTYPE = "%s"

                [model_providers.%s]
                name = "%s"
                base_url = "%s"
                wire_api = "responses"
                %s
                """.formatted(
                providerName,
                escapeToml(model),
                reasoningConfig,
                contextWindowConfig,
                authConfig,
                escapeToml(processLocale),
                escapeToml(processLocale),
                escapeToml(processLocale),
                providerName,
                isDeepSeek(modelConfig) ? "deepseek" : "Lingxi Agent",
                escapeToml(codexBaseUrl(modelConfig)),
                providerAuthConfig
        );
        return modelToml + mcpToml(mcpServers, mcpPaginationProxy);
    }

    private String mcpToml(List<RuntimeMcpServerConfig> servers, Path mcpPaginationProxy) {
        StringBuilder toml = new StringBuilder();
        for (RuntimeMcpServerConfig server : servers == null ? List.<RuntimeMcpServerConfig>of() : servers) {
            toml.append(System.lineSeparator())
                    .append("[mcp_servers.\"").append(escapeToml(server.code())).append("\"]")
                    .append(System.lineSeparator());
            if (server.transport() == RuntimeMcpTransport.STDIO) {
                List<String> arguments = new ArrayList<>();
                arguments.add(mcpPaginationProxy.toString());
                arguments.add(resolveCommand(server.command()));
                arguments.addAll(server.arguments());
                toml.append("command = \"python3\"").append(System.lineSeparator());
                toml.append("args = ").append(tomlArray(arguments)).append(System.lineSeparator());
            } else {
                toml.append("url = \"").append(escapeToml(server.url())).append("\"")
                        .append(System.lineSeparator());
                if (!server.headers().isEmpty()) {
                    toml.append("http_headers = ").append(tomlTable(server.headers()))
                            .append(System.lineSeparator());
                }
            }
            if (!server.toolAllowlist().isEmpty()) {
                toml.append("enabled_tools = ").append(tomlArray(new ArrayList<>(server.toolAllowlist())))
                        .append(System.lineSeparator());
            }
            toml.append("default_tools_approval_mode = \"approve\"").append(System.lineSeparator());
            if (server.transport() == RuntimeMcpTransport.STDIO && !server.environment().isEmpty()) {
                toml.append(System.lineSeparator()).append("[mcp_servers.\"")
                        .append(escapeToml(server.code())).append("\".env]")
                        .append(System.lineSeparator());
                server.environment().forEach((key, value) -> toml.append('"').append(escapeToml(key))
                        .append("\" = \"").append(escapeToml(value)).append('"')
                        .append(System.lineSeparator()));
            }
        }
        return toml.toString();
    }

    private String tomlArray(List<String> values) {
        return values.stream().map(value -> "\"" + escapeToml(value) + "\"")
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    private String tomlTable(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> "\"" + escapeToml(entry.getKey()) + "\" = \""
                        + escapeToml(entry.getValue()) + "\"")
                .collect(java.util.stream.Collectors.joining(", ", "{ ", " }"));
    }

    private String resolveCommand(String configuredCommand) {
        String command = blankToNull(configuredCommand);
        if (command == null || !command.contains("/") && !command.contains("\\")) {
            return command == null ? "" : command;
        }
        Path path = Path.of(command);
        if (path.isAbsolute()) {
            return path.normalize().toString();
        }
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path local = workingDirectory.resolve(path).normalize();
        if (Files.isRegularFile(local)) {
            return local.toString();
        }
        Path parent = workingDirectory.resolve("..").resolve(path).normalize();
        return Files.isRegularFile(parent) ? parent.toString() : local.toString();
    }

    /**
     * 返回当前模型在隔离 Codex 配置中使用的供应商 ID。
     *
     * @param modelConfig 当前任务模型配置
     * @return DeepSeek 官方供应商 ID 或平台通用供应商 ID
     */
    public String providerName(RuntimeModelConfig modelConfig) {
        return isDeepSeek(modelConfig) ? DEEPSEEK_PROVIDER_NAME : PROVIDER_NAME;
    }

    /**
     * 返回 Codex Responses provider 使用的服务地址。
     *
     * @param modelConfig 当前任务模型配置
     * @return 规范化后的服务地址
     */
    public String codexBaseUrl(RuntimeModelConfig modelConfig) {
        String value = modelConfig.baseUrl().trim().replaceAll("/+$", "");
        if (isDeepSeek(modelConfig)
                && ("https://api.deepseek.com".equalsIgnoreCase(value)
                || "https://api.deepseek.com/v1".equalsIgnoreCase(value))) {
            return "https://api.deepseek.com";
        }
        return normalizeBaseUrl(value);
    }

    /**
     * 判断当前模型是否需要使用 DeepSeek 官方 Codex 配置契约。
     *
     * @param modelConfig 当前任务模型配置
     * @return 供应商类型为 DeepSeek 时返回 true
     */
    public boolean isDeepSeek(RuntimeModelConfig modelConfig) {
        return modelConfig != null && modelConfig.providerType() != null
                && DEEPSEEK_PROVIDER_TYPE.equalsIgnoreCase(modelConfig.providerType().trim());
    }

    private void validateCodexModel(RuntimeModelConfig modelConfig) {
        if (isDeepSeek(modelConfig) && !DEEPSEEK_V4_FLASH_MODEL.equals(modelConfig.model().trim())) {
            throw new IllegalStateException("DeepSeek Codex 当前仅支持 deepseek-v4-flash 模型");
        }
    }

    private String authJson(String apiKey) {
        return """
                {"OPENAI_API_KEY":"%s"}
                """.formatted(escapeJson(apiKey));
    }

    public String normalizeBaseUrl(String baseUrl) {
        String value = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (value.endsWith("/models")) {
            value = value.substring(0, value.length() - "/models".length());
        }
        if (value.matches("(?s).*/models/[^/]+$")) {
            value = value.substring(0, value.lastIndexOf("/models"));
        }
        if (!value.endsWith("/v1") && !value.contains("/v1/")) {
            value = value + "/v1";
        }
        return value;
    }

    private String escapeToml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void trySetFileOwnerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
            ));
        } catch (Exception e) {
            log.info("设置 Codex 配置文件权限失败 path={} message={}", path, e.getMessage());
        }
    }

    private void trySetDirectoryOwnerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
            ));
        } catch (Exception e) {
            log.info("设置 Codex 配置目录权限失败 path={} message={}", path, e.getMessage());
        }
    }

    public record CodexSessionRef(String sessionId, String sessionPath) {
    }
}
