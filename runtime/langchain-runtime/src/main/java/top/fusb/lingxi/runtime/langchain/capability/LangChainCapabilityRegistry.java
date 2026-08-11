package top.fusb.lingxi.runtime.langchain.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.runtime.api.execution.RuntimeCommandDescriptor;
import top.fusb.lingxi.runtime.api.execution.RuntimeCommandOutputDescriptor;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionEnvironment;
import top.fusb.lingxi.runtime.api.execution.RuntimeSkillDescriptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class LangChainCapabilityRegistry {

    private final Map<String, RuntimeSkillDescriptor> skills;
    private final Map<String, RuntimeCommandDescriptor> commands;
    private final Map<String, SkillReadProgress> skillReadProgress = new ConcurrentHashMap<>();
    private final Set<String> inspectedSkills = ConcurrentHashMap.newKeySet();
    private final Consumer<Set<String>> inspectedSkillsConsumer;

    public LangChainCapabilityRegistry(RuntimeExecutionEnvironment environment) {
        this(environment, Set.of(), ignored -> { });
    }

    /**
     * 根据当前任务授权环境和持久化读取状态创建能力注册表。
     *
     * @param environment 当前任务授权的 Skill 与命令
     * @param restoredInspectedSkills 恢复前已经完整读取说明的 Skill
     * @param inspectedSkillsConsumer 新 Skill 解锁后的持久化回调
     */
    public LangChainCapabilityRegistry(RuntimeExecutionEnvironment environment,
                                       Collection<String> restoredInspectedSkills,
                                       Consumer<Set<String>> inspectedSkillsConsumer) {
        Map<String, RuntimeSkillDescriptor> skillMap = new LinkedHashMap<>();
        for (RuntimeSkillDescriptor skill : environment == null
                ? List.<RuntimeSkillDescriptor>of() : environment.skills()) {
            if (skill == null || skill.name() == null || skill.name().isBlank()) {
                continue;
            }
            if (skillMap.putIfAbsent(skill.name(), skill) != null) {
                throw new IllegalStateException("Skill 重复挂载：" + skill.name());
            }
        }
        skills = Map.copyOf(skillMap);

        Map<String, RuntimeCommandDescriptor> commandMap = new LinkedHashMap<>();
        for (RuntimeCommandDescriptor command : environment == null
                ? List.<RuntimeCommandDescriptor>of() : environment.commands()) {
            if (command == null || command.command() == null || command.command().isBlank()) {
                continue;
            }
            String key = normalizeCommand(command.command());
            if (commandMap.putIfAbsent(key, command) != null) {
                throw new IllegalStateException("运行时命令重复挂载：" + key);
            }
        }
        commands = Map.copyOf(commandMap);
        this.inspectedSkillsConsumer = inspectedSkillsConsumer == null ? ignored -> { } : inspectedSkillsConsumer;
        for (String skillName : restoredInspectedSkills == null ? List.<String>of() : restoredInspectedSkills) {
            if (skillName != null && skills.containsKey(skillName.trim())) {
                inspectedSkills.add(skillName.trim());
            }
        }
    }

    /**
     * 解析当前任务已授权 Skill 中可进入模型上下文的说明文件。
     *
     * @param skillName 当前任务挂载的 Skill name
     * @param relativePath `SKILL.md` 或 `references/` 下的相对路径
     * @return 校验后的普通文件真实路径
     * @throws Exception Skill 未授权、路径越界、文件不存在或包含符号链接时抛出
     */
    public Path skillFile(String skillName, String relativePath) throws Exception {
        String skill = skillName == null ? "" : skillName.trim();
        RuntimeSkillDescriptor descriptor = skills.get(skill);
        if (descriptor == null) {
            throw new IllegalArgumentException("Skill 未安装或当前任务未授权：" + skill);
        }
        String value = relativePath == null ? "" : relativePath.trim().replace('\\', '/');
        Path relative = Path.of(value).normalize();
        boolean readable = "SKILL.md".equals(value)
                || relative.getNameCount() > 1 && "references".equals(relative.getName(0).toString());
        if (!readable || relative.isAbsolute() || value.contains("..")) {
            throw new IllegalArgumentException("只能读取 Skill 的 SKILL.md 或 references 文件：" + relativePath);
        }
        Path skillRoot = Path.of(descriptor.sourcePath()).toAbsolutePath().normalize().toRealPath();
        if (!Files.isDirectory(skillRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(skillRoot)) {
            throw new IllegalStateException("Skill 来源目录无效：" + skill);
        }
        Path file = skillRoot.resolve(relative).normalize();
        if (!file.startsWith(skillRoot) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file)) {
            throw new IllegalArgumentException("Skill 说明文件不存在：" + relativePath);
        }
        Path realFile = file.toRealPath();
        if (!realFile.startsWith(skillRoot)) {
            throw new IllegalArgumentException("Skill 说明文件超出挂载目录：" + relativePath);
        }
        return realFile;
    }

    /**
     * 返回当前任务已挂载 Skill 的用户可见名称。
     *
     * @param skillName SKILL.md frontmatter 中的 name
     * @return 平台配置的展示名称；未配置展示名称时返回 Skill name
     * @throws IllegalArgumentException Skill 未安装或当前任务未授权时抛出
     */
    public String skillDisplayName(String skillName) {
        String name = skillName == null ? "" : skillName.trim();
        RuntimeSkillDescriptor descriptor = skills.get(name);
        if (descriptor == null) {
            throw new IllegalArgumentException("Skill 未安装或当前任务未授权：" + name);
        }
        String displayName = descriptor.displayName() == null ? "" : descriptor.displayName().trim();
        return displayName.isBlank() ? name : displayName;
    }

    /**
     * 记录模型已成功读取的 SKILL.md 行区间。只有从首行到文件末尾均已读取，命令才可解锁。
     *
     * @param skillName Skill name
     * @param startLine 本次返回的首行
     * @param endLine 本次返回的末行；未返回任何行时可小于 startLine
     * @param eofLine 已到达文件末尾时的总行数，否则为 null
     */
    public synchronized void recordSkillInstructionsRead(
            String skillName, int startLine, int endLine, Integer eofLine) {
        String name = skillName == null ? "" : skillName.trim();
        if (!skills.containsKey(name)) {
            throw new IllegalArgumentException("Skill 未安装或当前任务未授权：" + name);
        }
        if (startLine < 1 || endLine < startLine - 1 || eofLine != null && eofLine < 0) {
            throw new IllegalArgumentException("Skill 读取区间无效：" + startLine + "-" + endLine);
        }
        SkillReadProgress progress = skillReadProgress.computeIfAbsent(name, ignored -> new SkillReadProgress());
        if (progress.record(startLine, endLine, eofLine) && inspectedSkills.add(name)) {
            try {
                inspectedSkillsConsumer.accept(Set.copyOf(inspectedSkills));
            } catch (RuntimeException exception) {
                inspectedSkills.remove(name);
                throw exception;
            }
        }
    }

    public boolean skillInstructionsRead(String skillName) {
        return skillName != null && inspectedSkills.contains(skillName.trim());
    }

    public void requireSkillInstructionsRead(SkillCommand skillCommand) {
        if (skillCommand == null || !skillInstructionsRead(skillCommand.skillName())) {
            String skillName = skillCommand == null ? "" : skillCommand.skillName();
            throw new IllegalStateException("首次执行 Skill 命令前，必须在先前模型轮次完整读取 "
                    + skillName + "/SKILL.md；不得将读取说明与执行命令并行提交");
        }
    }

    /**
     * 在当前任务已授权 Skills 中唯一解析完整业务命令。
     *
     * @param command SKILL.md 声明的完整 group action 业务命令
     * @return 同时用于执行、结果绑定和事件渲染的命令描述
     * @throws IllegalArgumentException 命令格式非法或当前任务未授权时抛出
     */
    public SkillCommand resolveSkillCommand(String command) {
        String normalized = normalizeCommand(command);
        if (!normalized.matches("[a-z0-9]+(?:-[a-z0-9]+)* [a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException(
                    "Skill 命令必须是完整的 group action，例如 project-hub project-context；收到：" + command);
        }
        RuntimeCommandDescriptor descriptor = commands.get(normalized);
        if (descriptor == null || !descriptor.skillCommand() || !skills.containsKey(descriptor.moduleCode())) {
            String group = normalized.substring(0, normalized.indexOf(' '));
            List<String> candidates = skillCommands().stream()
                    .map(SkillCommand::command)
                    .filter(candidate -> candidate.startsWith(group + " "))
                    .sorted()
                    .toList();
            String suggestion = candidates.isEmpty()
                    ? "" : "；当前可用命令：" + String.join("、", candidates);
            throw new IllegalArgumentException("当前任务未声明该 Skill 业务命令：" + normalized + suggestion);
        }
        return new SkillCommand(descriptor);
    }

    /**
     * 返回当前任务已挂载 Skill 实际授权的业务命令，供动态工具 Schema 使用。
     *
     * @return 按完整业务命令排序的授权命令
     */
    public List<SkillCommand> skillCommands() {
        return commands.entrySet().stream()
                .filter(entry -> entry.getValue().skillCommand())
                .filter(entry -> skills.containsKey(entry.getValue().moduleCode()))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new SkillCommand(entry.getValue()))
                .toList();
    }

    public List<CapabilityOutput> outputs(SkillCommand skillCommand,
                                          String commandOutput, ObjectMapper objectMapper) {
        List<RuntimeCommandOutputDescriptor> bindings = skillCommand.descriptor().outputs();
        if (bindings.isEmpty()) {
            return List.of();
        }
        JsonNode result;
        try {
            result = objectMapper.readTree(commandOutput);
        } catch (Exception exception) {
            throw new IllegalStateException("Skill 命令声明了结构化输出，但返回结果不是 JSON", exception);
        }
        List<CapabilityOutput> outputs = new ArrayList<>();
        for (RuntimeCommandOutputDescriptor output : bindings) {
            String location = result.path(output.pathField()).asText("").trim();
            if (output.type() == null || output.type().isBlank()
                    || output.pathField() == null || output.pathField().isBlank() || location.isBlank()) {
                throw new IllegalStateException("Skill 命令结果不符合 lingxi.json commands.outputs");
            }
            outputs.add(new CapabilityOutput(output.type(), location, output.features()));
        }
        return List.copyOf(outputs);
    }

    public List<NativeCommand> nativeCommands() {
        List<NativeCommand> result = new ArrayList<>();
        Set<String> toolNames = new java.util.HashSet<>();
        commands.values().stream().filter(command -> !command.skillCommand()).forEach(command -> {
            String toolName = nativeToolName(command.code());
            if (!toolNames.add(toolName)) {
                throw new IllegalStateException("平台命令生成了重复工具名：" + toolName);
            }
            result.add(new NativeCommand(command, toolName));
        });
        return List.copyOf(result);
    }

    public NativeCommand commandForNativeTool(String toolName) {
        return nativeCommands().stream().filter(command -> command.toolName().equals(toolName)).findFirst().orElse(null);
    }

    public RuntimeCommandDescriptor resolveNativeCommand(String commandKey) {
        String normalized = normalizeCommand(commandKey).replace('.', ' ');
        RuntimeCommandDescriptor descriptor = commands.get(normalized);
        if (descriptor == null || descriptor.skillCommand()) {
            throw new IllegalArgumentException("平台命令未安装：" + commandKey);
        }
        return descriptor;
    }

    public record CapabilityOutput(String type, String location, Set<String> features) {
    }

    public record NativeCommand(RuntimeCommandDescriptor descriptor, String toolName) {

        public String commandKey() {
            return descriptor.code();
        }

        public String name() {
            return descriptor.name();
        }

        public String description() {
            return descriptor.description();
        }

        public String usage() {
            return descriptor.command();
        }

        public boolean capabilityCommand() {
            return false;
        }
    }

    public record SkillCommand(RuntimeCommandDescriptor descriptor) {

        public String skillName() {
            return descriptor.moduleCode();
        }

        public String command() {
            return normalizeCommand(descriptor.command());
        }

        public String actionLabel() {
            return descriptor.name();
        }
    }

    private static final class SkillReadProgress {

        private final BitSet lines = new BitSet();
        private Integer eofLine;

        private synchronized boolean record(int startLine, int endLine, Integer observedEofLine) {
            if (endLine >= startLine) {
                lines.set(startLine, endLine + 1);
            }
            if (observedEofLine != null) {
                eofLine = observedEofLine;
            }
            return eofLine != null && (eofLine == 0 || lines.nextClearBit(1) > eofLine);
        }
    }

    private String nativeToolName(String commandKey) {
        String readable = commandKey.replace(".", "__").replaceAll("[^A-Za-z0-9_-]", "_");
        if (readable.length() <= 64) {
            return readable;
        }
        String suffix = UUID.nameUUIDFromBytes(commandKey.getBytes(StandardCharsets.UTF_8))
                .toString().substring(0, 8);
        return readable.substring(0, 64 - suffix.length() - 1) + "_" + suffix;
    }

    private static String normalizeCommand(String command) {
        return command == null ? "" : command.trim().replaceAll("\\s+", " ");
    }
}
