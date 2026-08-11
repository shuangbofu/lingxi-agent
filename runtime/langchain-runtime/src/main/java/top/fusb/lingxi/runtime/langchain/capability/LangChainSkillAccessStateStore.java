package top.fusb.lingxi.runtime.langchain.capability;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;

public final class LangChainSkillAccessStateStore {

    private final Path stateFile;
    private final ObjectMapper objectMapper;

    public LangChainSkillAccessStateStore(Path stateFile, ObjectMapper objectMapper) {
        this.stateFile = stateFile.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    /**
     * 读取已经完整检查过说明的 Skill 集合。
     *
     * @return 已解锁 Skill 名称；状态文件不存在时返回空集合
     * @throws IllegalStateException 状态文件无法读取时抛出
     */
    public synchronized Set<String> read() {
        if (!Files.isRegularFile(stateFile)) {
            return Set.of();
        }
        try {
            State state = objectMapper.readValue(Files.readString(stateFile, StandardCharsets.UTF_8), State.class);
            return normalize(state == null ? null : state.inspectedSkills());
        } catch (Exception exception) {
            throw new IllegalStateException("读取 LangChain Skill 授权状态失败：" + stateFile, exception);
        }
    }

    /**
     * 原子保存已经完整检查过说明的 Skill 集合。
     *
     * @param inspectedSkills 已解锁 Skill 名称
     * @return 无返回值
     * @throws IllegalStateException 状态文件无法写入时抛出
     */
    public synchronized void write(Set<String> inspectedSkills) {
        try {
            Files.createDirectories(stateFile.getParent());
            Path temporaryFile = Files.createTempFile(stateFile.getParent(), "skill-access-", ".json.tmp");
            Files.writeString(temporaryFile,
                    objectMapper.writeValueAsString(new State(normalize(inspectedSkills))),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporaryFile, stateFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("保存 LangChain Skill 授权状态失败：" + stateFile, exception);
        }
    }

    /**
     * 删除当前执行的 Skill 授权状态。
     *
     * @return 无返回值
     * @throws IllegalStateException 状态文件无法删除时抛出
     */
    public synchronized void clear() {
        try {
            Files.deleteIfExists(stateFile);
        } catch (Exception exception) {
            throw new IllegalStateException("清理 LangChain Skill 授权状态失败：" + stateFile, exception);
        }
    }

    /**
     * 从上一会话恢复 Skill 授权状态。
     *
     * @param sourceFile 上一会话状态文件；不存在时清空当前状态
     * @return 是否恢复了已有状态
     * @throws IllegalStateException 状态文件无法读取或写入时抛出
     */
    public synchronized boolean restoreFrom(Path sourceFile) {
        Path source = sourceFile == null ? null : sourceFile.toAbsolutePath().normalize();
        if (source != null && source.equals(stateFile)) {
            return Files.isRegularFile(stateFile);
        }
        if (source == null || !Files.isRegularFile(source)) {
            clear();
            return false;
        }
        try {
            State state = objectMapper.readValue(Files.readString(source, StandardCharsets.UTF_8), State.class);
            write(state == null ? Set.of() : state.inspectedSkills());
            return true;
        } catch (Exception exception) {
            throw new IllegalStateException("恢复 LangChain Skill 授权状态失败：" + source, exception);
        }
    }

    /**
     * 根据聊天记忆文件确定同目录的 Skill 授权状态文件。
     *
     * @param memoryFile 聊天记忆文件
     * @return Skill 授权状态文件
     */
    public static Path stateFileForMemory(Path memoryFile) {
        return memoryFile.toAbsolutePath().normalize().resolveSibling("skill-access-state.json");
    }

    private Set<String> normalize(Set<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values == null ? Set.<String>of() : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim());
            }
        }
        return Set.copyOf(normalized);
    }

    private record State(Set<String> inspectedSkills) {
    }
}
