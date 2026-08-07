package top.fusb.lingxi.runtime.langchain.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.runtime.langchain.agent.evidence.LangChainEvidenceStore;
import top.fusb.lingxi.runtime.langchain.agent.model.LangChainContextBudget;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LangChainCompactingChatMemoryTest {

    @TempDir
    Path workspace;

    @Test
    void keepsFullActiveContextBeforeCompactionTrigger() {
        AtomicInteger compactionCalls = new AtomicInteger();
        LangChainCompactingChatMemory memory = new LangChainCompactingChatMemory(
                "task-82",
                transcriptStore(),
                new LangChainCompactionStateStore(workspace.resolve("context-state.json"), new ObjectMapper()),
                new CharacterTokenEstimator(),
                (originalTask, previousSummary, messages) -> {
                    compactionCalls.incrementAndGet();
                    return "不应触发压缩";
                },
                20_000,
                0.9D,
                8_000,
                2_000,
                2,
                2_000,
                1_000,
                1_000
        );
        memory.add(SystemMessage.from("平台规则"));
        memory.add(UserMessage.from("原始问题"));
        for (int index = 1; index <= 3; index++) {
            ToolExecutionRequest request = toolRequest("call-" + index, "search_code");
            memory.add(AiMessage.from(request));
            memory.add(ToolExecutionResultMessage.from(request, "完整工具结果" + index + "甲".repeat(1_500)));
        }

        assertThat(memory.messages())
                .filteredOn(ToolExecutionResultMessage.class::isInstance)
                .hasSize(3)
                .allSatisfy(message -> assertThat(((ToolExecutionResultMessage) message).text())
                        .startsWith("完整工具结果"));
        assertThat(compactionCalls).hasValue(0);
    }

    @Test
    void includesReservedToolSchemasWhenDecidingWhetherToCompact() {
        CharacterTokenEstimator estimator = new CharacterTokenEstimator();
        LangChainContextBudget contextBudget = new LangChainContextBudget(
                estimator, 30_000, 0.8D, null, null);
        contextBudget.reserveToolSpecifications(List.of(ToolSpecification.builder()
                .name("large_schema")
                .description("参".repeat(20_000))
                .build()));
        AtomicInteger compactionCalls = new AtomicInteger();
        LangChainCompactingChatMemory memory = new LangChainCompactingChatMemory(
                "schema-budget",
                new LangChainFileChatMemoryStore(workspace.resolve("schema-memory.json")),
                new LangChainCompactionStateStore(workspace.resolve("schema-state.json"), new ObjectMapper()),
                contextBudget,
                (originalTask, previousSummary, messages) -> {
                    compactionCalls.incrementAndGet();
                    return "已压缩历史";
                },
                2_000,
                1_000,
                1,
                1_000,
                1_000,
                1_000,
                () -> { },
                null);

        memory.add(UserMessage.from("原始问题"));
        memory.add(AiMessage.from("调查一" + "甲".repeat(1_300)));
        memory.add(AiMessage.from("调查二" + "乙".repeat(1_300)));
        memory.add(AiMessage.from("调查三" + "丙".repeat(1_300)));
        memory.add(AiMessage.from("调查四" + "丁".repeat(1_300)));

        assertThat(estimator.estimateTokenCountInMessages(memory.messages())).isLessThan(24_000);
        assertThat(compactionCalls).hasPositiveValue();
    }

    @Test
    void archivesConsumedSkillContractWithoutChangingTranscript() throws Exception {
        CharacterTokenEstimator estimator = new CharacterTokenEstimator();
        ObjectMapper objectMapper = new ObjectMapper();
        LangChainEvidenceStore evidenceStore = new LangChainEvidenceStore(
                workspace.resolve("contract-evidence"), objectMapper);
        LangChainActiveToolResultProjector projector = new LangChainActiveToolResultProjector(
                "task-contract", estimator, evidenceStore, 100);
        LangChainFileChatMemoryStore transcriptStore = new LangChainFileChatMemoryStore(
                workspace.resolve("contract-memory.json"));
        LangChainCompactingChatMemory memory = new LangChainCompactingChatMemory(
                "task-contract",
                transcriptStore,
                new LangChainCompactionStateStore(workspace.resolve("contract-state.json"), new ObjectMapper()),
                estimator,
                (originalTask, previousSummary, messages) -> "不应触发压缩",
                20_000,
                0.9D,
                8_000,
                2_000,
                2,
                2_000,
                1_000,
                1_000,
                () -> { },
                projector
        );
        String skillContract = "# Project Hub\n\nproject-hub project-context --project-id value\n"
                + "x".repeat(2_000);
        ToolExecutionRequest readSkill = toolRequest("skill-call", "read_skill_file");
        ToolExecutionRequest nextCommand = toolRequest("command-call", "run_skill_command");
        memory.add(UserMessage.from("调查项目"));
        memory.add(AiMessage.from(readSkill));
        memory.add(ToolExecutionResultMessage.from(readSkill, skillContract));
        memory.add(AiMessage.from(nextCommand));
        memory.add(ToolExecutionResultMessage.from(nextCommand, "project list"));

        List<ToolExecutionResultMessage> activeResults = memory.messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .toList();

        assertThat(activeResults).hasSize(2);
        assertThat(activeResults.get(0).text())
                .contains("langchain.active_tool_result_evidence", "read_evidence");
        assertThat(activeResults.get(1).text()).isEqualTo("project list");
        String evidenceId = objectMapper.readTree(activeResults.get(0).text()).path("evidenceId").asText();
        assertThat(evidenceStore.readAll(evidenceId).content()).isEqualTo(skillContract);
        assertThat(transcriptStore.getMessages("task-contract"))
                .filteredOn(ToolExecutionResultMessage.class::isInstance)
                .first()
                .satisfies(message -> assertThat(((ToolExecutionResultMessage) message).text())
                        .isEqualTo(skillContract));
        assertThat(evidenceStore.indexFile()).exists();
    }

    @Test
    void replacesStableSystemInstructionsInsteadOfAccumulatingThem() {
        LangChainCompactingChatMemory memory = new LangChainCompactingChatMemory(
                "task-82",
                transcriptStore(),
                new LangChainCompactionStateStore(workspace.resolve("context-state.json"), new ObjectMapper()),
                new CharacterTokenEstimator(),
                (originalTask, previousSummary, messages) -> "不应触发压缩",
                20_000,
                0.9D,
                8_000,
                2_000,
                2,
                2_000,
                1_000,
                1_000
        );
        memory.add(SystemMessage.from("上一轮场景规则"));
        memory.add(UserMessage.from("上一轮问题"));
        memory.add(SystemMessage.from("当前轮场景规则"));

        assertThat(memory.messages())
                .filteredOn(SystemMessage.class::isInstance)
                .singleElement()
                .isEqualTo(SystemMessage.from("当前轮场景规则"));
        assertThat(transcriptStore().getMessages("task-82"))
                .filteredOn(SystemMessage.class::isInstance)
                .hasSize(1);
    }

    @Test
    void commitsDeliveredResponseInsteadOfKeepingInvestigationDraft() {
        LangChainCompactingChatMemory memory = memory((originalTask, previousSummary, messages) -> "不应触发压缩");
        memory.add(SystemMessage.from("场景契约"));
        memory.add(UserMessage.from("说明业务影响"));
        memory.add(AiMessage.from("列出全部类名和方法"));

        memory.commitFinalResponse("业务影响说明");

        assertThat(memory.messages().get(memory.messages().size() - 1))
                .isEqualTo(AiMessage.from("业务影响说明"));
        assertThat(transcriptStore().getMessages("task-82").get(2))
                .isEqualTo(AiMessage.from("业务影响说明"));
    }

    @Test
    void preservesOriginalTaskAndFullTranscriptAfterCompaction() {
        List<String> previousSummaries = new ArrayList<>();
        LangChainCompactingChatMemory memory = memory((originalTask, previousSummary, messages) -> {
            previousSummaries.add(previousSummary);
            return "已确认：" + marker(messages);
        });

        memory.add(SystemMessage.from("平台规则"));
        memory.add(UserMessage.from("原始场景要求：必须输出查询结论和查询口径" + "问".repeat(1_000)));
        memory.add(AiMessage.from("第一批调查" + "甲".repeat(1_300)));
        memory.add(AiMessage.from("第二批调查" + "乙".repeat(1_300)));
        memory.add(AiMessage.from("第三批调查" + "丙".repeat(1_300)));

        List<ChatMessage> active = memory.messages();
        List<ChatMessage> transcript = transcriptStore().getMessages("task-82");

        assertThat(active).anySatisfy(message -> assertThat(message)
                .isEqualTo(UserMessage.from("原始场景要求：必须输出查询结论和查询口径" + "问".repeat(1_000))));
        assertThat(active).anySatisfy(message -> assertThat(message)
                .isInstanceOfSatisfying(AiMessage.class,
                        ai -> assertThat(ai.text()).contains("前序调查状态", "已确认")));
        assertThat(transcript).hasSize(5);
        assertThat(previousSummaries).containsExactly("");
    }

    @Test
    void keepsToolRequestAndAllResultsInTheSameCompactionBlock() {
        List<List<ChatMessage>> compactedBlocks = new ArrayList<>();
        LangChainCompactingChatMemory memory = memory((originalTask, previousSummary, messages) -> {
            compactedBlocks.add(messages);
            return "工具证据已整理";
        });
        ToolExecutionRequest first = toolRequest("call-1", "search_code");
        ToolExecutionRequest second = toolRequest("call-2", "get_code_snippet");

        memory.add(SystemMessage.from("平台规则"));
        memory.add(UserMessage.from("核查实现" + "问".repeat(1_000)));
        memory.add(AiMessage.from(List.of(first, second)));
        memory.add(ToolExecutionResultMessage.from(first, "搜索结果" + "甲".repeat(1_100)));
        memory.add(ToolExecutionResultMessage.from(second, "代码证据" + "乙".repeat(1_100)));
        memory.add(AiMessage.from("继续核查" + "丙".repeat(1_300)));
        memory.add(AiMessage.from("形成结论" + "丁".repeat(1_300)));

        assertThat(compactedBlocks).isNotEmpty();
        assertThat(compactedBlocks.get(0))
                .filteredOn(message -> message instanceof ToolExecutionResultMessage)
                .hasSize(2);
        assertThat(compactedBlocks.get(0))
                .filteredOn(message -> message instanceof AiMessage ai && ai.hasToolExecutionRequests())
                .hasSize(1);
    }

    @Test
    void mergesPreviousSummaryAndRestoresItFromDisk() {
        List<String> previousSummaries = new ArrayList<>();
        LangChainConversationCompactor compactor = (originalTask, previousSummary, messages) -> {
            previousSummaries.add(previousSummary);
            return (previousSummary.isBlank() ? "S1" : previousSummary + " + S2") + " " + marker(messages);
        };
        LangChainCompactingChatMemory memory = memory(compactor);
        memory.add(SystemMessage.from("平台规则"));
        memory.add(UserMessage.from("原始问题" + "问".repeat(1_000)));
        memory.add(AiMessage.from("调查一" + "甲".repeat(1_300)));
        memory.add(AiMessage.from("调查二" + "乙".repeat(1_300)));
        memory.add(AiMessage.from("调查三" + "丙".repeat(1_300)));
        memory.add(AiMessage.from("调查四" + "丁".repeat(1_300)));

        LangChainCompactingChatMemory restored = memory(compactor);

        assertThat(previousSummaries).hasSizeGreaterThanOrEqualTo(2);
        assertThat(previousSummaries.get(0)).isBlank();
        assertThat(previousSummaries.get(1)).contains("S1");
        assertThat(restored.messages()).anySatisfy(message -> assertThat(message)
                .isInstanceOfSatisfying(AiMessage.class, ai -> assertThat(ai.text()).contains("S1", "S2")));
    }

    @Test
    void leavesCompleteContextUntouchedWhenCompactionFailsBelowHardLimit() {
        LangChainCompactingChatMemory memory = memory((originalTask, previousSummary, messages) -> {
            throw new IllegalStateException("模型暂时不可用");
        });
        memory.add(SystemMessage.from("平台规则"));
        memory.add(UserMessage.from("原始问题" + "问".repeat(1_000)));
        memory.add(AiMessage.from("调查一" + "甲".repeat(1_300)));
        memory.add(AiMessage.from("调查二" + "乙".repeat(1_300)));

        assertThat(memory.messages()).hasSize(4);
        assertThat(transcriptStore().getMessages("task-82")).hasSize(4);
    }

    @Test
    void prunesOnlyStaleToolOutputsFromActiveView() {
        LangChainCompactingChatMemory memory = new LangChainCompactingChatMemory(
                "task-82",
                transcriptStore(),
                new LangChainCompactionStateStore(workspace.resolve("context-state.json"), new ObjectMapper()),
                new CharacterTokenEstimator(),
                (originalTask, previousSummary, messages) -> "不应触发压缩",
                20_000,
                0.9D,
                2_000,
                1_000,
                2,
                1_000,
                1_000,
                1_000
        );
        memory.add(SystemMessage.from("平台规则"));
        memory.add(UserMessage.from("原始问题"));
        for (int index = 1; index <= 3; index++) {
            ToolExecutionRequest request = toolRequest("call-" + index, "search_code");
            memory.add(AiMessage.from(request));
            memory.add(ToolExecutionResultMessage.from(request, "结果" + index + "甲".repeat(6_500)));
        }

        List<ToolExecutionResultMessage> activeResults = memory.messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .toList();
        List<ToolExecutionResultMessage> persistedResults = transcriptStore().getMessages("task-82").stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .toList();

        assertThat(activeResults).filteredOn(result -> result.text().contains("已从活动上下文裁剪")).hasSize(2);
        assertThat(activeResults.get(2).text()).startsWith("结果3");
        assertThat(persistedResults).allSatisfy(result -> assertThat(result.text()).startsWith("结果"));
    }

    @Test
    void emergencyProjectionFitsCompletedParallelStepWithoutChangingTranscript() {
        CharacterTokenEstimator estimator = new CharacterTokenEstimator();
        LangChainEvidenceStore evidenceStore = new LangChainEvidenceStore(
                workspace.resolve("evidence"), new ObjectMapper());
        LangChainActiveToolResultProjector projector = new LangChainActiveToolResultProjector(
                "task-capacity", estimator, evidenceStore, 1_000);
        LangChainFileChatMemoryStore transcriptStore = new LangChainFileChatMemoryStore(
                workspace.resolve("capacity-memory.json"));
        LangChainCompactingChatMemory memory = new LangChainCompactingChatMemory(
                "task-capacity",
                transcriptStore,
                new LangChainCompactionStateStore(workspace.resolve("capacity-state.json"), new ObjectMapper()),
                estimator,
                (originalTask, previousSummary, messages) -> "不应触发压缩",
                4_000,
                0.9D,
                1_000,
                1_000,
                1,
                1_000,
                1_000,
                1_000,
                () -> { },
                projector);
        ToolExecutionRequest first = toolRequest("parallel-1", "read_file");
        ToolExecutionRequest second = toolRequest("parallel-2", "read_file");
        memory.add(UserMessage.from("检查大文件"));
        memory.add(AiMessage.from(List.of(first, second)));

        memory.add(ToolExecutionResultMessage.from(first, "第一份" + "甲".repeat(5_000)));
        memory.add(ToolExecutionResultMessage.from(second, "第二份" + "乙".repeat(5_000)));

        List<ToolExecutionResultMessage> projected = memory.messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .toList();
        List<ToolExecutionResultMessage> persisted = transcriptStore.getMessages("task-capacity").stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .toList();

        assertThat(projected).hasSize(2)
                .allSatisfy(result -> assertThat(result.text())
                        .contains("langchain.active_tool_result_evidence"));
        assertThat(persisted.get(0).text()).startsWith("第一份");
        assertThat(persisted.get(1).text()).startsWith("第二份");
    }

    private LangChainCompactingChatMemory memory(LangChainConversationCompactor compactor) {
        return new LangChainCompactingChatMemory(
                "task-82",
                transcriptStore(),
                new LangChainCompactionStateStore(workspace.resolve("context-state.json"), new ObjectMapper()),
                new CharacterTokenEstimator(),
                compactor,
                6_000,
                0.55D,
                1_000,
                1_000,
                2,
                1_000,
                2_000,
                1_000
        );
    }

    private LangChainFileChatMemoryStore transcriptStore() {
        return new LangChainFileChatMemoryStore(workspace.resolve("chat-memory.json"));
    }

    private ToolExecutionRequest toolRequest(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }

    private String marker(List<ChatMessage> messages) {
        return messages.isEmpty() ? "无" : messages.get(0).type().name();
    }

    private static final class CharacterTokenEstimator implements TokenCountEstimator {

        @Override
        public int estimateTokenCountInText(String text) {
            return text == null ? 0 : text.length();
        }

        @Override
        public int estimateTokenCountInMessage(ChatMessage message) {
            return ChatMessageSerializer.messageToJson(message).length();
        }

        @Override
        public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
            int count = 0;
            for (ChatMessage message : messages) {
                count += estimateTokenCountInMessage(message);
            }
            return count;
        }
    }
}
