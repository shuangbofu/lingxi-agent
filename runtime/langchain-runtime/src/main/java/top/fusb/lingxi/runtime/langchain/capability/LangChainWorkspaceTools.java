package top.fusb.lingxi.runtime.langchain.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import top.fusb.lingxi.runtime.langchain.agent.LangChainExecutionContext;
import top.fusb.lingxi.runtime.langchain.agent.evidence.LangChainEvidenceStore;
import top.fusb.lingxi.runtime.api.support.RuntimeEnvironmentKit;
import top.fusb.lingxi.runtime.api.execution.RuntimeCommandDescriptor;
import top.fusb.lingxi.runtime.langchain.config.LangChainRuntimeProperties;
import top.fusb.lingxi.runtime.langchain.process.LangChainProcessRunner;
import top.fusb.lingxi.runtime.langchain.util.LangChainToolOutputKit;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LangChainWorkspaceTools {

    private final LangChainRuntimeProperties properties;
    private final LangChainExecutionContext context;
    private final ObjectMapper objectMapper;
    private final LangChainCapabilityRegistry capabilityRegistry;
    private final LangChainEvidenceStore evidenceStore;
    private final LangChainCapabilityOutputHandler outputHandler;

    public LangChainWorkspaceTools(LangChainRuntimeProperties properties,
                                   LangChainExecutionContext context,
                                   ObjectMapper objectMapper,
                                   LangChainCapabilityRegistry capabilityRegistry,
                                   LangChainEvidenceStore evidenceStore,
                                   LangChainCapabilityOutputHandler outputHandler) {
        this.properties = properties;
        this.context = context;
        this.objectMapper = objectMapper;
        this.capabilityRegistry = capabilityRegistry;
        this.evidenceStore = evidenceStore;
        this.outputHandler = outputHandler;
    }

    LangChainExecutionContext executionContext() {
        return context;
    }

    /**
     * 执行当前场景已经安装并允许使用的能力 CLI 命令。
     *
     * @param commandKey registry 中的命令键
     * @param arguments 不包含命令名前缀的参数数组
     * @return CLI 标准输出
     * @throws Exception 命令未授权、启动失败、超时或返回非零退出码时抛出
     */
    public String runCapabilityCommand(
            String commandKey,
            List<String> arguments
    ) throws Exception {
        RuntimeCommandDescriptor descriptor = capabilityRegistry.resolveNativeCommand(commandKey);
        String normalizedKey = descriptor.code();
        List<String> command = capabilityCommand(descriptor);
        if (arguments != null) {
            for (String argument : arguments) {
                if (argument == null || argument.indexOf('\0') >= 0) {
                    throw new IllegalArgumentException("能力命令参数非法");
                }
                command.add(argument);
            }
        }
        context.beginCapabilityCall(normalizedKey, arguments);
        LangChainProcessRunner.ProcessResult result = LangChainProcessRunner.execute(
                command,
                context.workspace(),
                runtimeEnvironment(),
                properties.getCapabilityCommandTimeout(),
                Math.max(properties.getToolOutputMaxChars(), properties.getWorkspaceFileMaxChars()),
                context
        );
        if (result.exitCode() != 0) {
            throw failedCommandException(normalizedKey, result);
        }
        List<LangChainCapabilityRegistry.CapabilityOutput> outputs = List.of();
        if (outputs.isEmpty()) {
            return limitCommandOutput(normalizedKey, arguments, result.output());
        }
        JsonNode commandResult = objectMapper.readTree(result.output());
        if (!(commandResult instanceof ObjectNode resultObject)) {
            throw new IllegalStateException("带资源输出的能力命令必须返回 JSON 对象：" + normalizedKey);
        }
        ArrayNode runtimeResources = resultObject.putArray("runtimeResources");
        for (LangChainCapabilityRegistry.CapabilityOutput output : outputs) {
            runtimeResources.add(outputHandler.handle(output));
        }
        return limitCommandOutput(normalizedKey, arguments, objectMapper.writeValueAsString(resultObject));
    }

    /**
     * 执行当前任务已挂载 Skill 声明的业务命令。
     *
     * @param command SKILL.md 命令章节声明的 group action 业务命令
     * @param arguments 业务命令之后的参数数组
     * @return 命令标准输出
     * @throws Exception Skill 或命令未授权、执行失败或超时时抛出
     */
    public String runSkillCommand(
            String command,
            List<String> arguments
    ) throws Exception {
        LangChainCapabilityRegistry.SkillCommand skillCommand = capabilityRegistry.resolveSkillCommand(command);
        capabilityRegistry.requireSkillInstructionsRead(skillCommand);
        List<String> processCommand = capabilityCommand(skillCommand.descriptor());
        List<String> values = arguments == null ? List.of() : arguments;
        for (String argument : values) {
            if (argument == null || argument.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Skill 脚本参数非法");
            }
            processCommand.add(argument);
        }
        String actionKey = skillCommand.command();
        context.beginCapabilityCall(actionKey, values);
        LangChainProcessRunner.ProcessResult result = LangChainProcessRunner.execute(
                processCommand,
                context.workspace(),
                runtimeEnvironment(),
                properties.getCapabilityCommandTimeout(),
                Math.max(properties.getToolOutputMaxChars(), properties.getWorkspaceFileMaxChars()),
                context
        );
        if (result.exitCode() != 0) {
            throw failedCommandException(actionKey, result);
        }
        List<LangChainCapabilityRegistry.CapabilityOutput> outputs = capabilityRegistry.outputs(
                skillCommand, result.output(), objectMapper);
        if (outputs.isEmpty()) {
            return limitCommandOutput(actionKey, values, result.output());
        }
        JsonNode commandResult = objectMapper.readTree(result.output());
        if (!(commandResult instanceof ObjectNode resultObject)) {
            throw new IllegalStateException("带结果绑定的 Skill 脚本必须返回 JSON 对象：" + actionKey);
        }
        ArrayNode runtimeResources = resultObject.putArray("runtimeResources");
        for (LangChainCapabilityRegistry.CapabilityOutput output : outputs) {
            runtimeResources.add(outputHandler.handle(output));
        }
        return limitCommandOutput(actionKey, values, objectMapper.writeValueAsString(resultObject));
    }

    /**
     * 按需读取当前任务已挂载 Skill 的说明文件，不暴露脚本和平台扩展文件。
     *
     * @param skillName SKILL.md frontmatter 中的 name
     * @param relativePath `SKILL.md` 或 `references/` 下的相对路径
     * @param startLine 可选起始行，从 1 开始
     * @param endLine 可选结束行，包含该行且不得小于 startLine
     * @return 带原文件行号的文本内容
     * @throws Exception Skill 未授权、文件不可读或行号范围无效时抛出
     */
    @Tool(name = "read_skill_file", value = {
            "读取已挂载 Agent Skill 的说明。首次使用某个 Skill 时先完整读取 SKILL.md；",
            "references 仅在 SKILL.md 明确引用且当前动作需要时读取。"
    })
    public String readSkillFile(
            @P("已挂载 Agent Skills 清单括号内的 Skill name") String skillName,
            @P("Skill 内相对路径，只允许 SKILL.md 或 references/... 文件") String relativePath,
            @P(value = "可选起始行号，从 1 开始", required = false) Integer startLine,
            @P(value = "可选结束行号，包含该行", required = false) Integer endLine
    ) throws Exception {
        context.beginToolCall("read_skill_file");
        SkillTextRead result = readSkillText(
                capabilityRegistry.skillFile(skillName, relativePath), startLine, endLine);
        String normalizedPath = relativePath == null ? "" : relativePath.trim().replace('\\', '/');
        if ("SKILL.md".equals(normalizedPath)) {
            capabilityRegistry.recordSkillInstructionsRead(
                    skillName, result.startLine(), result.endLine(), result.eofLine());
        }
        return result.content();
    }

    /**
     * 把能力之间需要传递的 JSON、SQL 或文本写入任务临时目录。
     *
     * @param relativePath 任务临时输入目录下的相对路径
     * @param content 文件内容
     * @return 写入后的工作区相对路径
     * @throws Exception 路径越界、内容过大或文件写入失败时抛出
     */
    @Tool(name = "write_workspace_file", value = {
            "将能力调用需要的 JSON、SQL 或文本写入当前任务的临时输入目录。只能写任务临时输入，不能修改仓库源码或运行时配置。"
    })
    public String writeWorkspaceFile(
            @P("临时输入目录下的相对文件路径，例如 query.sql 或 reports/query.json") String relativePath,
            @P("UTF-8 文件内容") String content
    ) throws Exception {
        String value = content == null ? "" : content;
        if (value.length() > properties.getWorkspaceFileMaxChars()) {
            throw new IllegalArgumentException("写入内容超过限制：" + properties.getWorkspaceFileMaxChars());
        }
        context.beginToolCall("write_workspace_file");
        Path configuredWorkspace = context.workspace().toAbsolutePath().normalize();
        Path workspace = configuredWorkspace.toRealPath();
        Path configuredAllowedRoot = Path.of(context.workspaceLayout().runtimeInputRoot())
                .toAbsolutePath().normalize();
        if (!configuredAllowedRoot.startsWith(configuredWorkspace)) {
            throw new IllegalArgumentException("任务临时输入目录超出当前处理范围");
        }
        Path allowedRoot = workspace.resolve(configuredWorkspace.relativize(configuredAllowedRoot)).normalize();
        secureCreateDirectories(workspace, allowedRoot);
        Path realRoot = allowedRoot.toRealPath();
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        Path input = Path.of(relativePath.trim());
        if (input.isAbsolute()) {
            throw new IllegalArgumentException("文件只能写入任务临时输入目录");
        }
        Path normalizedInput = input.normalize();
        Path requested = allowedRoot.resolve(normalizedInput).normalize();
        if (!requested.startsWith(allowedRoot)) {
            throw new IllegalArgumentException("文件只能写入任务临时输入目录");
        }
        Path target = realRoot.resolve(allowedRoot.relativize(requested)).normalize();
        if (!target.startsWith(realRoot)) {
            throw new IllegalArgumentException("文件只能写入任务临时输入目录");
        }
        secureCreateDirectories(realRoot, target.getParent());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) {
            throw new IllegalArgumentException("不能通过符号链接写入文件");
        }
        Files.writeString(target, value, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        return workspace.relativize(target).toString();
    }

    private List<String> capabilityCommand(RuntimeCommandDescriptor descriptor) throws Exception {
        Path executable = Path.of(descriptor.executable()).toAbsolutePath().normalize();
        Path realExecutable = executable.toRealPath();
        requireExecutable(realExecutable);
        List<String> command = new ArrayList<>();
        command.add(realExecutable.toString());
        command.addAll(descriptor.prefixArguments());
        return command;
    }

    private SkillTextRead readSkillText(Path file, Integer startLine, Integer endLine) throws Exception {
        int start = startLine == null ? 1 : startLine;
        int end = endLine == null ? Integer.MAX_VALUE : endLine;
        if (start < 1 || end < start) {
            throw new IllegalArgumentException("读取行号范围无效：" + start + "-" + end);
        }
        int hardLimit = Math.max(1, properties.getWorkspaceFileMaxChars());
        int limit = endLine == null
                ? Math.min(hardLimit, Math.max(1_000, properties.getToolOutputMaxChars()))
                : hardLimit;
        StringBuilder content = new StringBuilder(Math.min(limit, 16_384));
        int lineNumber = 0;
        int lastReadLine = start - 1;
        int nextStartLine = -1;
        boolean reachedEndOfFile = false;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while (true) {
                line = reader.readLine();
                if (line == null) {
                    reachedEndOfFile = true;
                    break;
                }
                lineNumber++;
                if (lineNumber < start) {
                    continue;
                }
                if (lineNumber > end) {
                    break;
                }
                String rendered = lineNumber + "\t" + line + "\n";
                if (content.length() + rendered.length() > limit) {
                    nextStartLine = lineNumber;
                    break;
                }
                content.append(rendered);
                lastReadLine = lineNumber;
            }
        } catch (java.nio.charset.MalformedInputException exception) {
            throw new IllegalArgumentException("Skill 说明文件必须是 UTF-8 文本", exception);
        }
        if (nextStartLine > 0) {
            content.append("[内容已分页，请使用 startLine=")
                    .append(nextStartLine)
                .append(" 继续读取]");
        }
        String text = content.isEmpty()
                ? "文件在指定行号范围内没有内容"
                : content.toString().stripTrailing();
        return new SkillTextRead(text, start, lastReadLine, reachedEndOfFile ? lineNumber : null);
    }

    private record SkillTextRead(String content, int startLine, int endLine, Integer eofLine) {
    }

    private Map<String, String> runtimeEnvironment() {
        Map<String, String> environment = new HashMap<>(
                RuntimeEnvironmentKit.materialize(context.environment(), System.getenv()));
        environment.put("PYTHONIOENCODING", "utf-8");
        return environment;
    }

    /**
     * 将超过模型单次读取预算的能力结果保存为任务证据，并返回合法的 JSON 摘要。
     *
     * @param commandKey 能力命令键
     * @param arguments 已传给能力命令的参数，仅用于记录脱敏来源摘要
     * @param output 能力命令完整输出
     * @return 可安全返回给模型的原始输出或证据摘要
     * @throws Exception 证据文件无法写入或摘要无法序列化时抛出
     */
    private String limitCommandOutput(String commandKey, List<String> arguments, String output) throws Exception {
        int limit = Math.max(1_000, properties.getToolOutputMaxChars());
        if (output == null || output.length() <= limit) {
            return output;
        }
        JsonNode parsed = null;
        try {
            parsed = objectMapper.readTree(output);
        } catch (Exception ignored) {
            // 非 JSON 输出仍按文本证据保存。
        }
        LangChainEvidenceStore.EvidenceReference evidence = evidenceStore.record(
                "capability_command", commandKey + " " + arguments, output);
        if (evidence.duplicate()) {
            return objectMapper.writeValueAsString(evidenceStore.duplicateResult(evidence));
        }

        ObjectNode summary = LangChainToolOutputKit.summarize(
                objectMapper,
                output,
                parsed,
                null,
                Math.min(limit, Math.max(0, properties.getToolOutputPreviewChars()))
        );
        evidenceStore.annotate(summary, evidence);
        return objectMapper.writeValueAsString(summary);
    }

    private IllegalStateException failedCommandException(
            String commandKey, LangChainProcessRunner.ProcessResult result) throws Exception {
        String output = result.output() == null ? "" : result.output();
        Path configuredWorkspace = context.workspace().toAbsolutePath().normalize();
        Path workspace = configuredWorkspace.toRealPath();
        Path configuredPrivateRuntimeRoot = Path.of(context.workspaceLayout().privateRuntimeRoot())
                .toAbsolutePath().normalize();
        if (!configuredPrivateRuntimeRoot.startsWith(configuredWorkspace)) {
            throw new IllegalArgumentException("平台私有运行目录超出当前处理范围");
        }
        Path privateRuntimeRoot = workspace.resolve(
                configuredWorkspace.relativize(configuredPrivateRuntimeRoot)).normalize();
        secureCreateDirectories(workspace, privateRuntimeRoot);
        Path evidenceDirectory = privateRuntimeRoot.resolve("capability-failures");
        secureCreateDirectories(privateRuntimeRoot, evidenceDirectory);
        Path outputFile = Files.createTempFile(evidenceDirectory, safeFilePart(commandKey) + "-", ".log");
        Files.writeString(outputFile, output, StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
        restrictOwnerAccess(outputFile);

        String redacted = LangChainToolOutputKit.redactSensitiveText(output);
        JsonNode parsed = null;
        try {
            parsed = objectMapper.readTree(redacted);
        } catch (Exception ignored) {
            // 非 JSON 错误输出保留限长文本预览。
        }
        ObjectNode summary = LangChainToolOutputKit.summarize(
                objectMapper,
                redacted,
                parsed,
                workspace.relativize(outputFile).toString(),
                Math.min(properties.getToolOutputMaxChars(), properties.getToolOutputPreviewChars())
        );
        summary.put("failed", true);
        summary.put("exitCode", result.exitCode());
        summary.put("privateEvidence", true);
        summary.put("nextAction", "根据脱敏摘要修正参数；私有证据仅供平台运维排查，不要重复执行相同命令。");
        return new IllegalStateException("能力命令执行失败：" + objectMapper.writeValueAsString(summary));
    }

    private void secureCreateDirectories(Path trustedRoot, Path directory) throws Exception {
        Path normalizedRoot = trustedRoot.toRealPath();
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        if (!normalizedDirectory.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("目录超出当前处理范围");
        }
        Path current = normalizedRoot;
        for (Path part : normalizedRoot.relativize(normalizedDirectory)) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException("目录包含不允许的符号链接或非目录节点：" + current);
                }
            } else {
                Files.createDirectory(current);
            }
        }
    }

    private void restrictOwnerAccess(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            ));
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            // 非 POSIX 文件系统继续依赖任务工作区权限隔离。
        }
    }

    private String safeFilePart(String value) {
        String normalized = value == null ? "capability" : value.replaceAll("[^A-Za-z0-9._-]+", "-");
        return normalized.isBlank() ? "capability" : normalized;
    }

    private Path resolveWorkspacePath(String value) throws Exception {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        Path input = Path.of(value);
        Path configuredWorkspace = context.workspace().toAbsolutePath().normalize();
        Path realWorkspace = context.workspace().toRealPath();
        Path requested = (input.isAbsolute() ? input : configuredWorkspace.resolve(input))
                .toAbsolutePath().normalize();
        Path relative;
        if (requested.startsWith(configuredWorkspace)) {
            relative = configuredWorkspace.relativize(requested);
        } else if (requested.startsWith(realWorkspace)) {
            relative = realWorkspace.relativize(requested);
        } else {
            throw new IllegalArgumentException("文件路径超出当前处理目录");
        }
        return realWorkspace.resolve(relative).normalize();
    }

    private void requireExecutable(Path executable) {
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw new IllegalStateException("能力运行时尚未准备：" + executable);
        }
    }

}
