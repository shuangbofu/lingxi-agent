package top.fusb.lingxi.runtime.langchain.agent.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LangChainFileChatMemoryStore implements ChatMemoryStore {

    private static final String INTERRUPTED_TOOL_RESULT =
            "服务进程在该工具返回结果前中断。本次动作未确认完成，请根据现有工作区状态决定是否重新执行。";

    private final Path memoryFile;

    public LangChainFileChatMemoryStore(Path memoryFile) {
        this.memoryFile = memoryFile.toAbsolutePath().normalize();
    }

    @Override
    public synchronized List<ChatMessage> getMessages(Object memoryId) {
        if (!Files.isRegularFile(memoryFile)) {
            return List.of();
        }
        try {
            String json = Files.readString(memoryFile, StandardCharsets.UTF_8);
            return json.isBlank() ? List.of() : ChatMessageDeserializer.messagesFromJson(json);
        } catch (Exception exception) {
            throw new IllegalStateException("读取 LangChain 恢复上下文失败：" + memoryFile, exception);
        }
    }

    @Override
    public synchronized void updateMessages(Object memoryId, List<ChatMessage> messages) {
        try {
            Files.createDirectories(memoryFile.getParent());
            Path temporaryFile = Files.createTempFile(memoryFile.getParent(), "chat-memory-", ".json.tmp");
            Files.writeString(temporaryFile, ChatMessageSerializer.messagesToJson(messages), StandardCharsets.UTF_8);
            try {
                Files.move(temporaryFile, memoryFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, memoryFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("保存 LangChain 恢复上下文失败：" + memoryFile, exception);
        }
    }

    @Override
    public synchronized void deleteMessages(Object memoryId) {
        try {
            Files.deleteIfExists(memoryFile);
        } catch (IOException exception) {
            throw new IllegalStateException("清理 LangChain 恢复上下文失败：" + memoryFile, exception);
        }
    }

    /**
     * 为服务中断时尚未返回结果的工具调用补入明确失败结果，保持消息协议完整。
     *
     * @param memoryId 当前执行的消息记忆标识
     * @return 补入的中断工具结果数量
     */
    public synchronized int completeInterruptedToolCalls(Object memoryId) {
        List<ChatMessage> messages = new ArrayList<>(getMessages(memoryId));
        Map<String, ToolExecutionRequest> pendingRequests = new LinkedHashMap<>();
        for (ChatMessage message : messages) {
            if (message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                aiMessage.toolExecutionRequests().forEach(request -> pendingRequests.put(request.id(), request));
            } else if (message instanceof ToolExecutionResultMessage resultMessage) {
                pendingRequests.remove(resultMessage.id());
            }
        }
        if (pendingRequests.isEmpty()) {
            return 0;
        }
        pendingRequests.values().forEach(request -> messages.add(
                ToolExecutionResultMessage.from(request.id(), request.name(), INTERRUPTED_TOOL_RESULT)));
        updateMessages(memoryId, messages);
        return pendingRequests.size();
    }

    /**
     * 将已完成会话的完整消息记录复制到当前会话存储。
     *
     * @param sourceFile 上一轮 RuntimeSessionRef 指向的消息文件
     * @return 恢复的消息数量
     * @throws IllegalStateException 来源会话不存在或内容无法解析时抛出
     */
    public synchronized int restoreFrom(Path sourceFile) {
        if (sourceFile == null) {
            throw new IllegalStateException("LangChain 续聊会话缺少持久化路径");
        }
        Path source = sourceFile.toAbsolutePath().normalize();
        if (source.equals(memoryFile)) {
            return getMessages(null).size();
        }
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("LangChain 续聊会话不存在：" + source);
        }
        try {
            String json = Files.readString(source, StandardCharsets.UTF_8);
            List<ChatMessage> messages = json.isBlank()
                    ? List.of() : ChatMessageDeserializer.messagesFromJson(json);
            updateMessages(null, messages);
            return messages.size();
        } catch (Exception exception) {
            throw new IllegalStateException("恢复 LangChain 续聊上下文失败：" + source, exception);
        }
    }

    public Path memoryFile() {
        return memoryFile;
    }
}
