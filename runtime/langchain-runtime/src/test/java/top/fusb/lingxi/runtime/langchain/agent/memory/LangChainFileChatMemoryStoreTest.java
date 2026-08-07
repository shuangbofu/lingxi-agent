package top.fusb.lingxi.runtime.langchain.agent.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LangChainFileChatMemoryStoreTest {

    @TempDir
    Path workspace;

    @Test
    void persistsMessagesAcrossStoreInstances() {
        Path memoryFile = workspace.resolve("chat-memory.json");
        LangChainFileChatMemoryStore firstStore = new LangChainFileChatMemoryStore(memoryFile);
        firstStore.updateMessages("task-1", List.of(UserMessage.from("原始问题")));

        LangChainFileChatMemoryStore restoredStore = new LangChainFileChatMemoryStore(memoryFile);

        assertThat(restoredStore.getMessages("task-1"))
                .singleElement()
                .isEqualTo(UserMessage.from("原始问题"));
    }

    @Test
    void persistsAssistantThinkingForToolCallContinuation() {
        Path memoryFile = workspace.resolve("chat-memory.json");
        LangChainFileChatMemoryStore store = new LangChainFileChatMemoryStore(memoryFile);
        AiMessage assistant = AiMessage.builder()
                .thinking("先定位数据，再调用查询工具")
                .toolExecutionRequests(List.of(toolRequest("call-thinking", "run_capability_command")))
                .build();

        store.updateMessages("task-thinking", List.of(assistant));

        assertThat(store.getMessages("task-thinking"))
                .singleElement()
                .isInstanceOfSatisfying(AiMessage.class, restored -> {
                    assertThat(restored.thinking()).isEqualTo("先定位数据，再调用查询工具");
                    assertThat(restored.toolExecutionRequests()).hasSize(1);
                });
    }

    @Test
    void completesOnlyToolCallsWithoutPersistedResults() {
        Path memoryFile = workspace.resolve("chat-memory.json");
        LangChainFileChatMemoryStore store = new LangChainFileChatMemoryStore(memoryFile);
        ToolExecutionRequest completed = toolRequest("call-1", "read_file");
        ToolExecutionRequest interrupted = toolRequest("call-2", "run_capability_command");
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("分析视频"));
        messages.add(AiMessage.from(List.of(completed, interrupted)));
        messages.add(ToolExecutionResultMessage.from(completed, "读取成功"));
        store.updateMessages("task-2", messages);

        int completedCount = store.completeInterruptedToolCalls("task-2");

        assertThat(completedCount).isEqualTo(1);
        assertThat(store.getMessages("task-2"))
                .filteredOn(ToolExecutionResultMessage.class::isInstance)
                .hasSize(2)
                .anySatisfy(message -> assertThat(((ToolExecutionResultMessage) message).id()).isEqualTo("call-2"));
    }

    private ToolExecutionRequest toolRequest(String id, String name) {
        return ToolExecutionRequest.builder()
                .id(id)
                .name(name)
                .arguments("{}")
                .build();
    }
}
