package top.fusb.lingxi.runtime.langchain.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class LangChainCompactionStateStore {

    private final Path stateFile;
    private final ObjectMapper objectMapper;

    public LangChainCompactionStateStore(Path stateFile, ObjectMapper objectMapper) {
        this.stateFile = stateFile.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    public synchronized State read() {
        if (!Files.isRegularFile(stateFile)) {
            return State.empty();
        }
        try {
            State state = objectMapper.readValue(Files.readString(stateFile, StandardCharsets.UTF_8), State.class);
            return state == null ? State.empty() : state;
        } catch (Exception exception) {
            throw new IllegalStateException("读取 LangChain 压缩状态失败：" + stateFile, exception);
        }
    }

    public synchronized void write(State state) {
        try {
            Files.createDirectories(stateFile.getParent());
            Path temporaryFile = Files.createTempFile(stateFile.getParent(), "context-state-", ".json.tmp");
            Files.writeString(temporaryFile, objectMapper.writeValueAsString(state), StandardCharsets.UTF_8);
            try {
                Files.move(temporaryFile, stateFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("保存 LangChain 压缩状态失败：" + stateFile, exception);
        }
    }

    public synchronized void clear() {
        try {
            Files.deleteIfExists(stateFile);
        } catch (Exception exception) {
            throw new IllegalStateException("清理 LangChain 压缩状态失败：" + stateFile, exception);
        }
    }

    /**
     * 恢复上一轮会话已经生成的压缩边界和摘要。
     *
     * @param sourceFile 上一轮压缩状态文件；不存在时清空当前状态
     * @return 是否恢复了已有压缩状态
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
            write(state == null ? State.empty() : state);
            return true;
        } catch (Exception exception) {
            throw new IllegalStateException("恢复 LangChain 压缩状态失败：" + source, exception);
        }
    }

    public static Path stateFileForMemory(Path memoryFile) {
        return memoryFile.toAbsolutePath().normalize().resolveSibling("context-state.json");
    }

    public record State(int compactedMessageCount, String summary) {

        public static State empty() {
            return new State(0, "");
        }
    }
}
