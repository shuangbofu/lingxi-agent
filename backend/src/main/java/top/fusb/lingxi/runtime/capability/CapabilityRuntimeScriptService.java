package top.fusb.lingxi.runtime.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.config.LingxiProperties;
import top.fusb.lingxi.definition.CapabilityCommandExtensionDefinition;
import top.fusb.lingxi.definition.CapabilityRuntimeModuleDefinition;
import top.fusb.lingxi.dto.CapabilityCommandOutputDefinition;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.runtime.api.support.RuntimeLocaleResolver;
import top.fusb.lingxi.runtime.api.execution.RuntimeCommandDescriptor;
import top.fusb.lingxi.runtime.api.execution.RuntimeCommandGuideDescriptor;
import top.fusb.lingxi.runtime.api.execution.RuntimeCommandOutputDescriptor;
import top.fusb.lingxi.runtime.api.execution.RuntimeCommandParameterDescriptor;
import top.fusb.lingxi.runtime.api.execution.RuntimeCommandParameterType;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionEnvironment;
import top.fusb.lingxi.runtime.api.execution.RuntimeSkillDescriptor;
import top.fusb.lingxi.runtime.api.event.RuntimeActionIcon;
import top.fusb.lingxi.runtime.api.execution.RuntimeWorkspaceLayout;
import top.fusb.lingxi.prompt.PromptTemplateService;
import top.fusb.lingxi.service.CapabilityConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class CapabilityRuntimeScriptService {

    private static final String LAUNCHER_FILE = "capability";
    private static final String REGISTRY_FILE = "registry.json";
    private static final String RUNTIME_CLIENT_FILE = "capability_runtime.py";
    private static final String RESOURCE_MEMORY_FILE = "resource_memory.py";

    private final LingxiProperties lingxiProperties;
    private final CapabilityRuntimeDefinitionService capabilityRuntimeDefinitionService;
    private final AgentRuntimeAccessService agentRuntimeAccessService;
    private final CapabilityConfigService capabilityConfigService;
    private final ObjectMapper objectMapper;
    private final PromptTemplateService promptTemplateService;

    /**
     * 为当前任务安装允许使用的能力模块并生成统一命令入口。
     *
     * @param taskId 任务 ID
     * @param workspace 当前任务的 Backend 工作区布局
     * @param ownerUsername 任务所属登录用户
     * @param allowedModuleCodes 当前任务定义显式挂载的能力模块 code
     * @param allowedCommands 当前场景按能力显式允许的命令 code；null 表示能力入口使用模块全部命令
     * @return 所有 Agent Runtime 共用的工作区执行环境
     * @throws BizException 能力文件或运行时入口生成失败时抛出
     */
    public RuntimeExecutionEnvironment ensureScript(Long taskId, RuntimeWorkspaceLayout workspace, String ownerUsername,
                                                    Set<String> allowedModuleCodes,
                                                    Map<String, Set<String>> allowedCommands) {
        try {
            Path root = runtimeRoot(taskId);
            Path launcherPath = root.resolve(LAUNCHER_FILE);
            resetRuntimeRoot(root);
            Files.createDirectories(root);
            Set<String> moduleCodes = allowedModuleCodes == null ? Set.of() : allowedModuleCodes;
            String runtimeToken = agentRuntimeAccessService.issue(taskId, moduleCodes, runtimePaths(moduleCodes));
            Map<String, Object> registry = installModules(root, moduleCodes, allowedCommands);
            registerPlatformCommands(registry);
            registry.put("baseUrl", lingxiProperties.getCapabilityRuntime().getBaseUrl());
            registry.put("pythonExecutable", lingxiProperties.getCapabilityRuntime().getPythonExecutable());
            registry.put("locale", capabilityLocale());
            registry.put("packageCacheRoot", Path.of(lingxiProperties.getCapabilityRuntime().getPackageCacheDir())
                    .toAbsolutePath().normalize().toString());
            registry.put("taskId", String.valueOf(taskId));
            registry.put("runtimeToken", runtimeToken);
            registry.put("ownerUsername", ownerUsername == null ? "" : ownerUsername);
            registry.put("workspacePath", workspace.executionRoot());
            registry.put("outputRoot", Path.of(workspace.artifactsRoot()).resolve("capability-outputs")
                    .toAbsolutePath().normalize().toString());
            registry.put("applicationRoot", Path.of("").toAbsolutePath().normalize().toString());
            Files.writeString(root.resolve(REGISTRY_FILE), objectMapper.writeValueAsString(registry), StandardCharsets.UTF_8);
            trySetOwnerOnly(root.resolve(REGISTRY_FILE));
            Files.writeString(root.resolve(RUNTIME_CLIENT_FILE), runtimeClientScript(), StandardCharsets.UTF_8);
            Files.writeString(root.resolve(RESOURCE_MEMORY_FILE), resourceMemoryScript(), StandardCharsets.UTF_8);
            Files.writeString(launcherPath, launcherScript(), StandardCharsets.UTF_8);
            trySetExecutable(launcherPath);
            writeSkillCommandShims(root, moduleCodes, allowedCommands);
            writeResourceMemoryShim(root);
            log.info("capability runtime ready taskId={} path={}", taskId, launcherPath);
            Map<String, String> variables = Map.of(
                    "LANG", capabilityLocale(),
                    "LC_ALL", capabilityLocale(),
                    "LC_CTYPE", capabilityLocale(),
                    "PYTHONIOENCODING", "utf-8"
            );
            return new RuntimeExecutionEnvironment(
                    variables,
                    List.of(root.resolve("bin").toString()),
                    List.of(root.toString()),
                    sensitiveValues(moduleCodes),
                    runtimeCommands(root, moduleCodes, allowedCommands),
                    runtimeSkills(moduleCodes),
                    List.of(new RuntimeCommandGuideDescriptor(
                            "resource-memory", "资源记忆", resourceMemoryGuide(moduleCodes)))
            );
        } catch (Exception e) {
            agentRuntimeAccessService.revoke(taskId);
            throw new BizException(ErrorCode.PROCESS_ERROR, ErrorSubCode.RUNTIME_EXEC_FAILED, "写入能力运行时入口失败：" + e.getMessage());
        }
    }

    /**
     * 撤销任务的内部能力运行时访问凭证。
     *
     * @param taskId 任务 ID
     * @return 无返回值
     */
    public void revokeAccess(Long taskId) {
        agentRuntimeAccessService.revoke(taskId);
    }

    private String capabilityLocale() {
        return RuntimeLocaleResolver.resolveProcessLocale(lingxiProperties.getCapabilityRuntime().getLocale())
                .orElse("C");
    }

    private List<String> sensitiveValues(Set<String> moduleCodes) {
        return capabilityConfigService.sensitiveValues(moduleCodes);
    }

    private Path runtimeRoot(Long taskId) {
        return Path.of(lingxiProperties.getTask().getContentDir())
                .resolve("runtime")
                .resolve("task-" + taskId)
                .toAbsolutePath()
                .normalize();
    }

    /**
     * 重建当前任务的受控私有运行时目录，避免恢复或重试时继续暴露已撤销的命令入口。
     *
     * @param root `contentDir/runtime/task-<id>` 私有运行时目录
     * @return 无返回值
     * @throws Exception 旧运行时文件无法清理时抛出
     */
    private void resetRuntimeRoot(Path root) throws Exception {
        Path runtimeBase = Path.of(lingxiProperties.getTask().getContentDir())
                .resolve("runtime").toAbsolutePath().normalize();
        if (!root.startsWith(runtimeBase) || root.equals(runtimeBase) || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private List<RuntimeCommandDescriptor> runtimeCommands(Path root, Set<String> moduleCodes,
                                                            Map<String, Set<String>> allowedCommands) {
        Map<String, RuntimeCommandDescriptor> commands = new LinkedHashMap<>();
        for (CapabilityRuntimeModuleDefinition module : capabilityRuntimeDefinitionService.modules()) {
            if (!moduleCodes.contains(module.getModuleCode())) {
                continue;
            }
            for (CapabilityCommandExtensionDefinition definition : selectedCommands(module, allowedCommands)) {
                String command = publicCommand(definition.getCommand());
                String[] parts = command.split(" ", 2);
                String actionCode = command.replace(' ', '.');
                RuntimeCommandDescriptor existing = commands.putIfAbsent(command, new RuntimeCommandDescriptor(
                        module.getModuleCode(), actionCode, command, definition.getDisplayName(),
                        definition.getDescription(), root.resolve("bin").resolve(parts[0]).toString(),
                        List.of(parts[1]), runtimeOutputs(definition.getOutputs()), null, List.of(),
                        definition.getIcon(), definition.getExecutionMode()));
                if (existing != null) {
                    throw new IllegalStateException("能力业务命令重复：" + command);
                }
            }
        }
        registerPlatformDescriptor(commands, root, "resource-memory search", "检索资源记忆", "search",
                moduleCodes);
        registerPlatformDescriptor(commands, root, "resource-memory save", "保存资源记忆", "save",
                moduleCodes);
        registerPlatformDescriptor(commands, root, "resource-memory invalidate", "失效资源记忆", "invalidate",
                moduleCodes);
        return List.copyOf(commands.values());
    }

    private List<RuntimeSkillDescriptor> runtimeSkills(Set<String> moduleCodes) {
        return capabilityRuntimeDefinitionService.modules().stream()
                .filter(module -> moduleCodes.contains(module.getModuleCode()))
                .map(module -> new RuntimeSkillDescriptor(module.getModuleCode(), module.getDisplayName(),
                        module.getModuleDirectory().toAbsolutePath().normalize().toString()))
                .toList();
    }

    private List<RuntimeCommandOutputDescriptor> runtimeOutputs(List<CapabilityCommandOutputDefinition> outputs) {
        return (outputs == null ? List.<CapabilityCommandOutputDefinition>of() : outputs).stream()
                .map(output -> new RuntimeCommandOutputDescriptor(output.getType(), output.getPathField(),
                        output.getFeatures() == null ? Set.of() : Set.copyOf(output.getFeatures())))
                .toList();
    }

    private void registerPlatformDescriptor(Map<String, RuntimeCommandDescriptor> commands, Path root,
                                            String command, String name, String action,
                                            Set<String> moduleCodes) {
        List<RuntimeCommandParameterDescriptor> parameters;
        String description;
        if ("search".equals(action)) {
            description = "在自动召回候选不足时，检索当前任务已挂载能力过去确认的可复用资源";
            parameters = List.of(
                    new RuntimeCommandParameterDescriptor("query", "--query", RuntimeCommandParameterType.STRING,
                            true, "资源名称、稳定引用或检索描述"),
                    new RuntimeCommandParameterDescriptor("includeRelated", "--include-related",
                            RuntimeCommandParameterType.BOOLEAN, false,
                            "是否包含只能作为线索的主题相关结果；默认 false"));
        } else {
            String providerCodes = String.join(", ", new TreeSet<>(moduleCodes));
            String providerRule = "providerCode 必须是当前任务已挂载 Agent Skill code 之一：["
                    + providerCodes + "]，且当前任务必须已经成功调用该 Skill；一个 Skill 挂载多个服务配置时 "
                    + "sourceConfigId 必填，只有一个配置时可为 null";
            description = "save".equals(action)
                    ? "保存已经由当前 Skill 证据确认且不含敏感信息的可复用资源摘要。" + providerRule
                    + "。file 指向工作区 JSON 文件，格式："
                    + "{\"providerCode\":\"mounted-skill-code\",\"sourceConfigId\":null,"
                    + "\"entries\":[{\"ref\":\"stable-ref\",\"kind\":\"provider-defined-kind\","
                    + "\"name\":\"name\",\"description\":\"summary\",\"aliases\":[],\"labels\":[],"
                    + "\"relations\":[],\"searchText\":\"terms\",\"revision\":\"\"}]}"
                    : "使已经被当前 Skill 证据证伪的资源摘要失效。" + providerRule
                    + "。file 指向工作区 JSON 文件，格式："
                    + "{\"providerCode\":\"mounted-skill-code\",\"sourceConfigId\":null,"
                    + "\"refs\":[\"stable-ref\"]}";
            parameters = List.of(new RuntimeCommandParameterDescriptor(
                    "file", "--file", RuntimeCommandParameterType.STRING, true,
                    "当前任务工作区内符合本工具描述格式的 JSON 文件路径"));
        }
        RuntimeActionIcon icon = switch (action) {
            case "search" -> RuntimeActionIcon.MAGNIFYING_GLASS;
            case "save" -> RuntimeActionIcon.FILE_PLUS;
            case "invalidate" -> RuntimeActionIcon.ARROW_COUNTER_CLOCKWISE;
            default -> RuntimeActionIcon.WRENCH;
        };
        commands.put(command, new RuntimeCommandDescriptor(null, command.replace(' ', '.'), command, name,
                description, root.resolve("bin/resource-memory").toString(), List.of(action), List.of(),
                "resource-memory", parameters, icon,
                "search".equals(action)
                        ? top.fusb.lingxi.runtime.api.execution.RuntimeToolExecutionMode.READ_ONLY
                        : top.fusb.lingxi.runtime.api.execution.RuntimeToolExecutionMode.SERIAL));
    }

    /**
     * 使用当前任务实际挂载的能力编码渲染资源记忆平台说明。
     *
     * @param moduleCodes 当前任务允许使用的能力模块编码
     * @return 同时用于首次按需读取和恢复上下文的资源记忆说明
     */
    private String resourceMemoryGuide(Set<String> moduleCodes) {
        return promptTemplateService.render("workspace-resource-memory",
                Map.of("providerCodes", String.join(", ", new TreeSet<>(moduleCodes))));
    }

    private List<CapabilityCommandExtensionDefinition> selectedCommands(
            CapabilityRuntimeModuleDefinition module, Map<String, Set<String>> allowedCommands) {
        List<CapabilityCommandExtensionDefinition> commands = module.getCommands() == null
                ? List.of() : module.getCommands();
        if (allowedCommands == null || !allowedCommands.containsKey(module.getModuleCode())) {
            return commands;
        }
        Set<String> allowed = allowedCommands.get(module.getModuleCode());
        if (allowed == null || allowed.isEmpty()) {
            return List.of();
        }
        return commands.stream().filter(command -> {
            String publicCommand = publicCommand(command.getCommand());
            return allowed.contains(publicCommand) || allowed.contains(publicCommand.replace(' ', '.'));
        }).toList();
    }

    private String publicCommand(String command) {
        String value = command == null ? "" : command.trim().replaceAll("\\s+", " ");
        if (!value.matches("[a-z0-9]+(?:-[a-z0-9]+)* [a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalStateException("能力动作必须使用 group action 命令：" + command);
        }
        return value;
    }

    private Map<String, Object> installModules(Path root, Set<String> allowedModuleCodes,
                                               Map<String, Set<String>> allowedCommands) throws Exception {
        Map<String, Object> skills = new LinkedHashMap<>();
        Path modulesRoot = root.resolve("skills").normalize();
        Files.createDirectories(modulesRoot);
        for (CapabilityRuntimeModuleDefinition module : capabilityRuntimeDefinitionService.modules()) {
            if (!allowedModuleCodes.contains(module.getModuleCode())) {
                continue;
            }
            Path source = module.getModuleDirectory();
            Path target = modulesRoot.resolve(module.getModuleCode()).normalize();
            if (!target.startsWith(modulesRoot)) {
                continue;
            }
            copyDirectory(source, target);
            Map<String, Object> skill = new LinkedHashMap<>();
            skill.put("directory", root.relativize(target).toString());
            skill.put("displayName", module.getDisplayName());
            skill.put("entrypoint", module.getEntrypoint());
            skill.put("commands", selectedCommands(module, allowedCommands));
            skills.put(module.getModuleCode(), skill);
        }
        Map<String, Object> registry = new LinkedHashMap<>();
        registry.put("skills", skills);
        registry.put("commands", new LinkedHashMap<>());
        return registry;
    }

    /**
     * 将平台原生命令写入任务 registry，使所有 Runtime 通过同一份命令契约执行。
     *
     * @param registry 当前任务的命令 registry
     * @return 无返回值
     */
    @SuppressWarnings("unchecked")
    private void registerPlatformCommands(Map<String, Object> registry) {
        Object commandObject = registry.get("commands");
        if (!(commandObject instanceof Map<?, ?> values)) {
            throw new IllegalStateException("能力命令 registry 结构无效");
        }
        Map<String, Object> commands = (Map<String, Object>) values;
        registerPlatformCommand(commands, "resource-memory.search", "检索资源记忆", "search");
        registerPlatformCommand(commands, "resource-memory.save", "保存资源记忆", "save");
        registerPlatformCommand(commands, "resource-memory.invalidate", "失效资源记忆", "invalidate");
    }

    private void registerPlatformCommand(Map<String, Object> commands, String key, String name, String action) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("name", name);
        command.put("description", "平台资源记忆命令");
        command.put("outputs", List.of());
        command.put("executable", "bin/resource-memory");
        command.put("prefixArguments", List.of(action));
        if (commands.putIfAbsent(key, command) != null) {
            throw new IllegalStateException("能力命令占用了平台保留命令：" + key);
        }
    }

    /**
     * 汇总当前任务已挂载模块声明的内部运行时接口路径。
     *
     * @param allowedModuleCodes 当前任务允许使用的能力模块 code
     * @return 已校验并去重的运行时接口路径前缀
     * @throws BizException 模块声明了非法或越界路径时抛出
     */
    private Set<String> runtimePaths(Set<String> allowedModuleCodes) {
        return Set.of();
    }

    private void copyDirectory(Path source, Path target) throws Exception {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                if (relative.toString().equals(REGISTRY_FILE)) {
                    continue;
                }
                Path destination = target.resolve(relative).normalize();
                if (!destination.startsWith(target)) {
                    continue;
                }
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                    if (destination.getFileName().toString().endsWith(".py")) {
                        trySetExecutable(destination);
                    }
                }
            }
        }
    }

    /**
     * 为 shell Runtime 生成面向模型的业务命令入口，脚本路径和运行环境只在私有 launcher 内处理。
     *
     * @param root 当前任务的能力运行时目录
     * @param moduleCodes 当前任务允许使用的能力模块编码
     * @return 无返回值
     * @throws Exception 命令入口写入失败时抛出
     */
    private void writeSkillCommandShims(Path root, Set<String> moduleCodes,
                                        Map<String, Set<String>> allowedCommands) throws Exception {
        Set<String> groups = new TreeSet<>();
        Set<String> commands = new TreeSet<>();
        for (CapabilityRuntimeModuleDefinition module : capabilityRuntimeDefinitionService.modules()) {
            if (!moduleCodes.contains(module.getModuleCode())) {
                continue;
            }
            for (CapabilityCommandExtensionDefinition definition : selectedCommands(module, allowedCommands)) {
                String command = publicCommand(definition.getCommand());
                if (!commands.add(command)) {
                    throw new IllegalStateException("能力业务命令重复：" + command);
                }
                groups.add(command.split(" ", 2)[0]);
            }
        }
        Path bin = root.resolve("bin").normalize();
        Files.createDirectories(bin);
        for (String group : groups) {
            if ("resource-memory".equals(group)) {
                throw new IllegalStateException("能力命令占用了平台保留命令：" + group);
            }
            Path shellShim = bin.resolve(group).normalize();
            Files.writeString(shellShim, skillShellShim(group), StandardCharsets.UTF_8);
            trySetExecutable(shellShim);
            Files.writeString(bin.resolve(group + ".cmd"), skillWindowsShim(group), StandardCharsets.UTF_8);
        }
    }


    /**
     * 安装平台通用资源记忆命令，使 Agent 可以独立于具体能力检索和保存资源摘要。
     *
     * @param root 当前任务的能力运行时目录
     * @return 无返回值
     * @throws Exception 命令脚本写入失败时抛出
     */
    private void writeResourceMemoryShim(Path root) throws Exception {
        Path bin = root.resolve("bin").normalize();
        Files.createDirectories(bin);
        Path shellShim = bin.resolve("resource-memory").normalize();
        Files.writeString(shellShim, """
                #!/usr/bin/env sh
                ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
                PYTHON_EXECUTABLE=%s
                LINGXI_LOCALE=%s
                export LANG="$LINGXI_LOCALE"
                export LC_ALL="$LINGXI_LOCALE"
                export LC_CTYPE="$LINGXI_LOCALE"
                exec "$PYTHON_EXECUTABLE" "$ROOT/%s" "$@"
                """.formatted(
                shellQuote(lingxiProperties.getCapabilityRuntime().getPythonExecutable()),
                shellQuote(capabilityLocale()),
                RESOURCE_MEMORY_FILE));
        trySetExecutable(shellShim);
        Files.writeString(bin.resolve("resource-memory.cmd"), """
                @echo off
                python "%%~dp0..\\%s" %%*
                """.formatted(RESOURCE_MEMORY_FILE), StandardCharsets.UTF_8);
    }

    private String skillShellShim(String group) {
        return """
                #!/usr/bin/env sh
                ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
                PYTHON_EXECUTABLE=%s
                LINGXI_LOCALE=%s
                export LANG="$LINGXI_LOCALE"
                export LC_ALL="$LINGXI_LOCALE"
                export LC_CTYPE="$LINGXI_LOCALE"
                exec "$PYTHON_EXECUTABLE" "$ROOT/capability" --command %s "$@"
                """.formatted(shellQuote(lingxiProperties.getCapabilityRuntime().getPythonExecutable()), shellQuote(capabilityLocale()), shellQuote(group));
    }

    private String skillWindowsShim(String group) {
        return """
                @echo off
                python "%%~dp0..\\capability" --command %s %%*
                """.formatted(group);
    }

    private String shellQuote(String value) {
        String text = value == null || value.isBlank() ? "python3" : value;
        return "'" + text.replace("'", "'\"'\"'") + "'";
    }

    private String launcherScript() {
        return """
                #!/usr/bin/env python3
                import hashlib
                import json
                import os
                import platform
                import re
                import subprocess
                import sys
                import time

                ROOT = os.path.dirname(os.path.abspath(__file__))
                REGISTRY_PATH = os.path.join(ROOT, "registry.json")
                MAX_CAPABILITY_STDOUT_CHARS = 40000
                JSON_PREVIEW_ITEMS = 8

                def file_sha256(path):
                    digest = hashlib.sha256()
                    with open(path, "rb") as file:
                        for chunk in iter(lambda: file.read(1024 * 1024), b""):
                            digest.update(chunk)
                    return digest.hexdigest()

                def acquire_install_lock(lock_dir):
                    while True:
                        try:
                            os.makedirs(lock_dir)
                            return True
                        except FileExistsError:
                            time.sleep(0.2)

                def release_install_lock(lock_dir):
                    try:
                        os.rmdir(lock_dir)
                    except OSError:
                        pass

                def ensure_python_requirements(entry, env, registry):
                    script = os.path.join(ROOT, entry["script"])
                    requirements = os.path.join(os.path.dirname(script), "requirements.txt")
                    if not os.path.isfile(requirements):
                        return True
                    module = entry.get("module") or "unknown"
                    current_hash = file_sha256(requirements)
                    cache_root = registry.get("packageCacheRoot") or os.path.join(ROOT, "python-packages")
                    python_tag = getattr(sys.implementation, "cache_tag", None) or "python-{}.{}".format(
                        sys.version_info[0], sys.version_info[1]
                    )
                    runtime_tag = re.sub(
                        r"[^A-Za-z0-9_.-]+",
                        "-",
                        "{}-{}-{}".format(python_tag, sys.platform, platform.machine() or "unknown"),
                    )
                    package_dir = os.path.join(cache_root, module, runtime_tag, current_hash)
                    marker = os.path.join(package_dir, ".requirements.sha256")
                    env["PYTHONPATH"] = package_dir + (os.pathsep + env.get("PYTHONPATH", "") if env.get("PYTHONPATH") else "")
                    installed_hash = ""
                    if os.path.isfile(marker):
                        with open(marker, "r", encoding="utf-8") as file:
                            installed_hash = file.read().strip()
                    if current_hash != installed_hash:
                        os.makedirs(package_dir, exist_ok=True)
                        lock_dir = package_dir + ".lock"
                        acquire_install_lock(lock_dir)
                        try:
                            if os.path.isfile(marker):
                                with open(marker, "r", encoding="utf-8") as file:
                                    installed_hash = file.read().strip()
                            if current_hash == installed_hash:
                                return True
                            process = subprocess.run(
                                [
                                    sys.executable,
                                    "-m",
                                    "pip",
                                    "install",
                                    "--disable-pip-version-check",
                                    "--no-input",
                                    "--upgrade",
                                    "--target",
                                    package_dir,
                                    "-r",
                                    requirements,
                                ],
                                universal_newlines=True,
                                stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE,
                            )
                            if process.returncode != 0:
                                print(f"能力 {module} 的 Python 依赖安装失败，请检查 pip 或网络/内网镜像配置。", file=sys.stderr)
                                if process.stderr:
                                    print(process.stderr.strip(), file=sys.stderr)
                                elif process.stdout:
                                    print(process.stdout.strip(), file=sys.stderr)
                                return False
                            with open(marker, "w", encoding="utf-8") as file:
                                file.write(current_hash)
                        finally:
                            release_install_lock(lock_dir)
                    return True

                def usage(registry):
                    print("Usage: capability <skill-name> <scripts/script-path> [args...]", file=sys.stderr)
                    print("Available skills:", file=sys.stderr)
                    for skill in sorted(registry.get("skills", {})):
                        print(f"  {skill}", file=sys.stderr)

                def resolve_public_command(registry, group, action):
                    command_key = f"{group} {action}"
                    matches = []
                    for skill_name, skill in registry.get("skills", {}).items():
                        for definition in skill.get("commands", []):
                            if definition.get("command") == command_key:
                                matches.append((skill_name, skill.get("entrypoint"), [f"{group}.{action}"]))
                    if len(matches) != 1:
                        print(f"Unknown or ambiguous capability command: {group} {action}", file=sys.stderr)
                        return None
                    return matches[0]

                def safe_filename(value):
                    chars = []
                    for char in value:
                        chars.append(char if char.isalnum() or char in ".-_" else "-")
                    return "".join(chars).strip("-") or "capability-output"

                def compact_json_value(value):
                    if isinstance(value, dict):
                        result = {}
                        for key, item in value.items():
                            if isinstance(item, list):
                                result[key] = [compact_json_value(child) for child in item[:JSON_PREVIEW_ITEMS]]
                                if len(item) > JSON_PREVIEW_ITEMS:
                                    result[key + "Truncated"] = True
                                    result[key + "OriginalCount"] = len(item)
                            else:
                                result[key] = compact_json_value(item)
                        return result
                    if isinstance(value, list):
                        result = [compact_json_value(item) for item in value[:JSON_PREVIEW_ITEMS]]
                        if len(value) > JSON_PREVIEW_ITEMS:
                            result.append({"truncated": True, "originalCount": len(value)})
                        return result
                    return value

                def compact_stdout(command, stdout, registry):
                    if len(stdout) <= MAX_CAPABILITY_STDOUT_CHARS:
                        return stdout
                    output_root = registry.get("outputRoot") or os.path.join(ROOT, "outputs")
                    os.makedirs(output_root, exist_ok=True)
                    extension = "txt"
                    parsed = None
                    try:
                        parsed = json.loads(stdout)
                        extension = "json"
                    except Exception:
                        parsed = None
                    filename = f"{int(time.time() * 1000)}-{safe_filename(command)}.{extension}"
                    output_path = os.path.abspath(os.path.join(output_root, filename))
                    with open(output_path, "w", encoding="utf-8") as file:
                        file.write(stdout)
                    if parsed is not None:
                        response = compact_json_value(parsed)
                        if not isinstance(response, dict):
                            response = {"items": response}
                        response["outputTruncated"] = True
                        response["outputFile"] = output_path
                        response["originalCharCount"] = len(stdout)
                        return json.dumps(response, ensure_ascii=False)
                    return json.dumps({
                        "outputTruncated": True,
                        "outputFile": output_path,
                        "originalCharCount": len(stdout),
                        "preview": stdout[:MAX_CAPABILITY_STDOUT_CHARS],
                    }, ensure_ascii=False)

                def main():
                    with open(REGISTRY_PATH, "r", encoding="utf-8") as file:
                        registry = json.load(file)
                    if len(sys.argv) < 3:
                        usage(registry)
                        return 2
                    if sys.argv[1] == "--command":
                        if len(sys.argv) < 4:
                            print("Capability command requires an action", file=sys.stderr)
                            return 2
                        resolved = resolve_public_command(registry, sys.argv[2], sys.argv[3])
                        if not resolved:
                            return 2
                        skill_name, script_path, argument_prefix = resolved
                        script_arguments = [*argument_prefix, *sys.argv[4:]]
                    else:
                        skill_name = sys.argv[1]
                        script_path = sys.argv[2]
                        script_arguments = sys.argv[3:]
                    script_path = script_path.replace("\\\\", "/") if script_path else ""
                    skill = registry.get("skills", {}).get(skill_name)
                    if not skill or not re.fullmatch(r"scripts/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*", script_path):
                        usage(registry)
                        return 2
                    skill_root = os.path.abspath(os.path.join(ROOT, skill["directory"]))
                    scripts_root = os.path.abspath(os.path.join(skill_root, "scripts"))
                    script = os.path.abspath(os.path.join(skill_root, script_path))
                    if not script.startswith(scripts_root + os.sep) or not os.path.isfile(script):
                        print("Skill script is missing or outside scripts directory", file=sys.stderr)
                        return 2
                    entry = {
                        "module": skill_name,
                        "script": os.path.relpath(script, ROOT),
                    }
                    command = script_arguments[0] if script_arguments else script_path
                    env = os.environ.copy()
                    env["AGENT_RUNTIME_BASE_URL"] = registry.get("baseUrl", "")
                    env["AGENT_RUNTIME_TASK_ID"] = registry.get("taskId", "")
                    env["AGENT_RUNTIME_TOKEN"] = registry.get("runtimeToken", "")
                    env["AGENT_RUNTIME_OWNER_USERNAME"] = registry.get("ownerUsername", "")
                    env["AGENT_RUNTIME_COMMAND"] = command
                    env["AGENT_RUNTIME_MODULE_CODE"] = entry.get("module", "")
                    env["AGENT_RUNTIME_COMMAND_CODE"] = ""
                    env["AGENT_RUNTIME_WORKSPACE"] = registry.get("workspacePath", "")
                    env["AGENT_RUNTIME_APPLICATION_ROOT"] = registry.get("applicationRoot", "")
                    locale = registry.get("locale") or "C"
                    env["LANG"] = locale
                    env["LC_ALL"] = locale
                    env["LC_CTYPE"] = locale
                    env["PYTHONIOENCODING"] = "utf-8"
                    env["PYTHONPATH"] = ROOT + (os.pathsep + env.get("PYTHONPATH", "") if env.get("PYTHONPATH") else "")
                    if not ensure_python_requirements(entry, env, registry):
                        return 1
                    process = subprocess.run(
                        [sys.executable, script, *script_arguments],
                        env=env,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                    )
                    stdout = process.stdout.decode("utf-8", "replace") if process.stdout else ""
                    stderr = process.stderr.decode("utf-8", "replace") if process.stderr else ""
                    if stdout:
                        sys.stdout.write(compact_stdout(command, stdout, registry))
                    if stderr:
                        sys.stderr.write(stderr)
                    return process.returncode

                if __name__ == "__main__":
                    raise SystemExit(main())
                """;
    }

    private String runtimeClientScript() {
        return """
                import json
                import os
                import sys
                import urllib.error
                import urllib.parse
                import urllib.request

                BASE_URL = os.environ["AGENT_RUNTIME_BASE_URL"].rstrip("/")
                CURRENT_TASK_ID = os.environ.get("AGENT_RUNTIME_TASK_ID", "")
                RUNTIME_TOKEN = os.environ.get("AGENT_RUNTIME_TOKEN", "")
                CURRENT_MODULE_CODE = os.environ.get("AGENT_RUNTIME_MODULE_CODE", "")
                CURRENT_COMMAND_CODE = os.environ.get("AGENT_RUNTIME_COMMAND_CODE", "")
                CURRENT_WORKSPACE = os.environ.get("AGENT_RUNTIME_WORKSPACE", "")

                def _scoped(params=None, include_task=False):
                    result = dict(params or {})
                    if include_task and CURRENT_TASK_ID:
                        result["taskId"] = CURRENT_TASK_ID
                    return result

                DEFAULT_REQUEST_TIMEOUT_SECONDS = 20

                def request_json(method, path, params=None, data=None, timeout_seconds=None):
                    response = fetch_json(method, path, params, data, timeout_seconds)
                    sys.stdout.write(json.dumps(response, ensure_ascii=False))
                    return 0

                def unwrap_response(value):
                    if isinstance(value, dict) and "code" in value and "data" in value:
                        return value.get("data")
                    return value

                def fetch_json(method, path, params=None, data=None, timeout_seconds=None):
                    query = urllib.parse.urlencode({key: value for key, value in (params or {}).items() if value not in (None, "")})
                    url = f"{BASE_URL}{path}" + (f"?{query}" if query else "")
                    body = None if data is None else json.dumps(data, ensure_ascii=False).encode("utf-8")
                    request = urllib.request.Request(url, data=body, headers={
                        "Content-Type": "application/json",
                        "X-Agent-Runtime-Token": RUNTIME_TOKEN,
                    }, method=method)
                    try:
                        with urllib.request.urlopen(request, timeout=timeout_seconds or DEFAULT_REQUEST_TIMEOUT_SECONDS) as response:
                            text = response.read().decode("utf-8")
                    except urllib.error.HTTPError as exc:
                        sys.stdout.write(exc.read().decode("utf-8", errors="replace"))
                        raise SystemExit(1)
                    return unwrap_response(json.loads(text)) if text else None

                def service_config_data(module_code=None, config_id=None, required=True):
                    if config_id:
                        return capability_config_data(config_id)
                    code = str(module_code or CURRENT_MODULE_CODE or "").strip().lower()
                    if not code:
                        if not required:
                            return None
                        print("service capability module code is missing", file=sys.stderr)
                        raise SystemExit(2)
                    configs = fetch_json("GET", "/api/agent-runtime/capability-configs", _scoped(include_task=True)) or []
                    matches = [
                        item for item in configs
                        if item.get("runtime") is not True
                        and str(item.get("capabilityCode") or "").strip().lower() == code
                    ]
                    if len(matches) == 1:
                        return capability_config_data(matches[0]["id"])
                    if not matches and not required:
                        return None
                    if not matches:
                        print(f"missing service config for capability: {module_code or CURRENT_MODULE_CODE}", file=sys.stderr)
                    else:
                        print(f"multiple service configs for capability: {module_code or CURRENT_MODULE_CODE}", file=sys.stderr)
                        for item in matches:
                            label = item.get("name") or item.get("id")
                            print(f"  id={item.get('id')} name={label}", file=sys.stderr)
                    raise SystemExit(2)

                def runtime_context():
                    return request_json("GET", "/api/agent-runtime/context", _scoped(include_task=True))

                def runtime_context_data():
                    return fetch_json("GET", "/api/agent-runtime/context", _scoped(include_task=True))

                def runtime_context_put(values=None):
                    return fetch_json("POST", "/api/agent-runtime/context", _scoped(include_task=True), {
                        "values": values or [],
                        "capabilityConfigs": [],
                    })

                def runtime_context_value(*keys):
                    context = runtime_context_data() or {}
                    for section in ("values", "inputValues"):
                        for item in context.get(section) or []:
                            key = str(item.get("key") or "")
                            value = str(item.get("value") or "").strip()
                            if key in keys and value:
                                return value
                    return ""

                def capability_config(config_id):
                    return request_json("GET", f"/api/agent-runtime/capability-configs/{config_id}", _scoped(include_task=True))

                def capability_config_data(config_id):
                    return fetch_json("GET", f"/api/agent-runtime/capability-configs/{config_id}", _scoped(include_task=True))

                def interaction_create(question, input_type="TEXT", options=None, fields=None, actions=None, content=None, required=True, placeholder=None, answer_hint=None, default_value=None, context_key=None, timeout_seconds=None):
                    return fetch_json("POST", "/api/agent-runtime/interactions", _scoped(include_task=True), {
                        "question": question,
                        "inputType": input_type,
                        "options": options or [],
                        "fields": fields or [],
                        "actions": actions or [],
                        "content": content,
                        "required": required,
                        "placeholder": placeholder,
                        "answerHint": answer_hint,
                        "defaultValue": default_value,
                        "contextKey": context_key,
                        "timeoutSeconds": timeout_seconds,
                    })

                def interaction_wait(interaction_id, timeout_seconds=None):
                    wait_timeout = timeout_seconds or 3700
                    return fetch_json("GET", f"/api/agent-runtime/interactions/{interaction_id}/wait", _scoped({"timeoutSeconds": timeout_seconds}, include_task=True), timeout_seconds=wait_timeout + 5)

                def interaction_ask(question, input_type="TEXT", options=None, fields=None, actions=None, content=None, required=True, placeholder=None, answer_hint=None, default_value=None, context_key=None, timeout_seconds=None):
                    created = interaction_create(question, input_type, options, fields, actions, content, required, placeholder, answer_hint, default_value, context_key, timeout_seconds)
                    return interaction_wait(created["id"], timeout_seconds)

                """;
    }

    private String resourceMemoryScript() {
        return """
                #!/usr/bin/env python3
                import json
                import os
                import sys
                import urllib.error
                import urllib.parse
                import urllib.request

                ROOT = os.path.dirname(os.path.abspath(__file__))
                REGISTRY_PATH = os.path.join(ROOT, "registry.json")
                MAX_INPUT_BYTES = 1024 * 1024

                def option(args, name):
                    for index, item in enumerate(args):
                        if item == name and index + 1 < len(args):
                            return args[index + 1]
                        if item.startswith(name + "="):
                            return item[len(name) + 1:]
                    return None

                def request_json(registry, method, path, params=None, data=None):
                    values = dict(params or {})
                    values["taskId"] = registry.get("taskId", "")
                    query = urllib.parse.urlencode({key: value for key, value in values.items() if value not in (None, "")})
                    url = registry.get("baseUrl", "").rstrip("/") + path + ("?" + query if query else "")
                    body = None if data is None else json.dumps(data, ensure_ascii=False).encode("utf-8")
                    request = urllib.request.Request(url, data=body, headers={
                        "Content-Type": "application/json",
                        "X-Agent-Runtime-Token": registry.get("runtimeToken", ""),
                    }, method=method)
                    try:
                        with urllib.request.urlopen(request, timeout=20) as response:
                            text = response.read().decode("utf-8")
                    except urllib.error.HTTPError as exc:
                        print(exc.read().decode("utf-8", errors="replace"), file=sys.stderr)
                        return None
                    value = json.loads(text) if text else None
                    if isinstance(value, dict) and "success" in value and value.get("success") is not True:
                        print(value.get("message") or "resource memory request failed", file=sys.stderr)
                        return None
                    if isinstance(value, dict) and "code" in value and "data" in value:
                        return value.get("data")
                    return value

                def workspace_file(registry, value):
                    workspace = os.path.abspath(registry.get("workspacePath", ""))
                    path = os.path.abspath(os.path.expanduser(value or ""))
                    if not workspace or not (path == workspace or path.startswith(workspace + os.sep)):
                        print("resource memory file must be inside the current task workspace", file=sys.stderr)
                        return None
                    if not os.path.isfile(path) or os.path.getsize(path) > MAX_INPUT_BYTES:
                        print("resource memory file is missing or exceeds 1 MB", file=sys.stderr)
                        return None
                    return path

                def main():
                    with open(REGISTRY_PATH, "r", encoding="utf-8") as file:
                        registry = json.load(file)
                    if len(sys.argv) < 2:
                        print("Usage: resource-memory <search|save|invalidate> [options]", file=sys.stderr)
                        return 2
                    action = sys.argv[1]
                    args = sys.argv[2:]
                    if action == "search":
                        query = option(args, "--query")
                        if not query:
                            print("missing required argument: --query", file=sys.stderr)
                            return 2
                        include_related = "--include-related" in args
                        result = request_json(registry, "GET", "/api/agent-runtime/resources/search", {
                            "query": query,
                            "includeRelated": str(include_related).lower(),
                        })
                    elif action == "save":
                        path = workspace_file(registry, option(args, "--file"))
                        if not path:
                            return 2
                        with open(path, "r", encoding="utf-8") as file:
                            payload = json.load(file)
                        result = request_json(registry, "POST", "/api/agent-runtime/resources", data=payload)
                    elif action == "invalidate":
                        path = workspace_file(registry, option(args, "--file"))
                        if not path:
                            return 2
                        with open(path, "r", encoding="utf-8") as file:
                            payload = json.load(file)
                        result = request_json(registry, "POST", "/api/agent-runtime/resources/invalidate", data=payload)
                    else:
                        print("unsupported resource-memory action: " + action, file=sys.stderr)
                        return 2
                    if result is None:
                        return 1
                    sys.stdout.write(json.dumps(result, ensure_ascii=False))
                    return 0

                if __name__ == "__main__":
                    raise SystemExit(main())
                """;
    }

    private void trySetExecutable(Path runtimePath) {
        try {
            Files.setPosixFilePermissions(runtimePath, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE
            ));
        } catch (Exception e) {
            log.info("设置能力运行时入口执行权限失败 path={} message={}", runtimePath, e.getMessage());
        }
    }

    private void trySetOwnerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            ));
        } catch (Exception e) {
            log.info("设置能力运行时文件权限失败 path={} message={}", path, e.getMessage());
        }
    }
}
