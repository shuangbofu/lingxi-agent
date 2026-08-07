package top.fusb.lingxi.runtime.langchain.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.runtime.langchain.agent.evidence.LangChainEvidenceStore;
import top.fusb.lingxi.runtime.langchain.util.LangChainToolOutputKit;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LangChainActiveToolResultProjectorTest {

    @TempDir
    Path tempDir;

    @Test
    void archivesOlderCompletedStepsAndKeepsNewestParallelStepVerbatim() throws Exception {
        Files.createDirectories(tempDir.resolve("runtime-state"));
        ObjectMapper objectMapper = new ObjectMapper();
        LangChainEvidenceStore evidenceStore = new LangChainEvidenceStore(
                tempDir.resolve("runtime-state/evidence"), objectMapper);
        LangChainActiveToolResultProjector projector = new LangChainActiveToolResultProjector(
                "execution-1", new CharacterTokenEstimator(), evidenceStore, 1_000);
        ToolExecutionRequest first = request("call-1");
        ToolExecutionRequest secondA = request("call-2a");
        ToolExecutionRequest secondB = request("call-2b");
        ToolExecutionResultMessage firstResult = ToolExecutionResultMessage.builder()
                .id(first.id())
                .toolName(first.name())
                .text("第一步原文" + "甲".repeat(2_000))
                .isError(true)
                .attributes(Map.of("trace", "kept"))
                .build();
        ToolExecutionResultMessage secondResultA = ToolExecutionResultMessage.from(
                secondA, "第二步结果A" + "乙".repeat(2_000));
        ToolExecutionResultMessage secondResultB = ToolExecutionResultMessage.from(
                secondB, "第二步结果B" + "丙".repeat(2_000));
        List<ChatMessage> messages = List.of(
                UserMessage.from("调查问题"),
                AiMessage.from(first),
                firstResult,
                AiMessage.from(List.of(secondA, secondB)),
                secondResultA,
                secondResultB);

        List<ChatMessage> projected = projector.projectCurrentTurn(messages, false);
        ToolExecutionResultMessage projectedFirst = (ToolExecutionResultMessage) projected.get(2);

        assertThat(projectedFirst.text()).contains(
                "langchain.active_tool_result_evidence", "read_evidence",
                "\"contentType\":\"text\"",
                "\"recommendedOperation\":\"read_evidence\"");
        assertThat(projectedFirst.isError()).isTrue();
        assertThat(projectedFirst.attributes()).containsEntry("trace", "kept");
        assertThat(((ToolExecutionResultMessage) projected.get(4)).text()).startsWith("第二步结果A");
        assertThat(((ToolExecutionResultMessage) projected.get(5)).text()).startsWith("第二步结果B");
        assertThat(firstResult.text()).startsWith("第一步原文");

        String evidenceId = objectMapper.readTree(projectedFirst.text()).path("evidenceId").asText();
        LangChainEvidenceStore.EvidenceSlice slice = evidenceStore.read(evidenceId, 0, 20_000);
        assertThat(slice.content()).isEqualTo(firstResult.text());
        assertThat(slice.sha256()).isEqualTo(LangChainEvidenceStore.sha256(firstResult.text()));

        projector.projectCurrentTurn(messages, false);
        var catalog = objectMapper.readTree(evidenceStore.indexFile().toFile());
        assertThat(catalog.path("evidence").get(0).path("observationCount").asInt()).isEqualTo(1);
    }

    @Test
    void emergencyProjectionMayArchiveNewestCompletedStep() throws Exception {
        Files.createDirectories(tempDir.resolve("emergency-state"));
        LangChainEvidenceStore evidenceStore = new LangChainEvidenceStore(
                tempDir.resolve("emergency-state/evidence"), new ObjectMapper());
        LangChainActiveToolResultProjector projector = new LangChainActiveToolResultProjector(
                "execution-2", new CharacterTokenEstimator(), evidenceStore, 1_000);
        ToolExecutionRequest request = request("latest-call");
        List<ChatMessage> messages = List.of(
                UserMessage.from("调查问题"),
                AiMessage.from(request),
                ToolExecutionResultMessage.from(request, "最新步骤" + "甲".repeat(2_000)));

        assertThat(((ToolExecutionResultMessage) projector.projectCurrentTurn(messages, false).get(2)).text())
                .startsWith("最新步骤");
        assertThat(((ToolExecutionResultMessage) projector.projectCurrentTurn(messages, true).get(2)).text())
                .contains("langchain.active_tool_result_evidence");
    }

    @Test
    void jsonPlaceholderNamesCompleteEvidenceQueryOperation() throws Exception {
        Files.createDirectories(tempDir.resolve("json-state"));
        LangChainEvidenceStore evidenceStore = new LangChainEvidenceStore(
                tempDir.resolve("json-state/evidence"), new ObjectMapper());
        LangChainActiveToolResultProjector projector = new LangChainActiveToolResultProjector(
                "execution-json", new CharacterTokenEstimator(), evidenceStore, 1_000);
        ToolExecutionRequest request = request("json-call");
        String content = "{\"items\":[" + "{\"id\":1,\"qualifiedName\":\"demo.Item\"},".repeat(1_000)
                + "{\"id\":2,\"qualifiedName\":\"demo.Other\"}]}";
        List<ChatMessage> messages = List.of(
                UserMessage.from("调查问题"),
                AiMessage.from(request),
                ToolExecutionResultMessage.from(request, content));

        String placeholder = ((ToolExecutionResultMessage) projector
                .projectCurrentTurn(messages, true).get(2)).text();

        assertThat(placeholder).contains(
                "\"contentType\":\"json\"",
                "\"recommendedOperation\":\"query_evidence\"",
                "\"collectionItemFields\":{\"items\":[\"id\",\"qualifiedName\"]}",
                "\"readInstructions\"");
    }

    @Test
    void describesTopLevelArrayItemFieldsForTheFirstEvidenceQuery() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String content = "[{\"route\":\"/tasks\",\"handler\":\"list\"},"
                + "{\"route\":\"/tasks/{id}\",\"handler\":\"detail\",\"method\":\"GET\"}]";

        var description = LangChainToolOutputKit.describeEvidenceStructure(objectMapper, content);

        assertThat(description.path("valueType").asText()).isEqualTo("array");
        assertThat(description.path("itemType").asText()).isEqualTo("object");
        assertThat(description.path("itemFields")).containsExactly(
                objectMapper.getNodeFactory().textNode("route"),
                objectMapper.getNodeFactory().textNode("handler"),
                objectMapper.getNodeFactory().textNode("method"));
    }

    @Test
    void identifiesTopLevelStringArraysForCurrentElementQueries() {
        ObjectMapper objectMapper = new ObjectMapper();

        var description = LangChainToolOutputKit.describeEvidenceStructure(
                objectMapper, "[\"first message\",\"second message\"]");

        assertThat(description.path("valueType").asText()).isEqualTo("array");
        assertThat(description.path("itemType").asText()).isEqualTo("string");
        assertThat(description.path("itemCount").asInt()).isEqualTo(2);
    }

    @Test
    void reusesEvidenceAlreadyExternalizedByAToolResult() throws Exception {
        Files.createDirectories(tempDir.resolve("reused-state"));
        ObjectMapper objectMapper = new ObjectMapper();
        LangChainEvidenceStore evidenceStore = new LangChainEvidenceStore(
                tempDir.resolve("reused-state/evidence"), objectMapper);
        LangChainActiveToolResultProjector projector = new LangChainActiveToolResultProjector(
                "execution-reused", new CharacterTokenEstimator(), evidenceStore, 100);
        String original = "{\"queries\":[" + "{\"id\":1},".repeat(1_000) + "{\"id\":2}]}";
        LangChainEvidenceStore.EvidenceReference originalReference = evidenceStore.record(
                "capability_command", "project-hub project-list", original);
        var summary = LangChainToolOutputKit.summarize(
                objectMapper, original, objectMapper.readTree(original), null, 1_000);
        evidenceStore.annotate(summary, originalReference);
        ToolExecutionRequest first = request("capability-call", "run_skill_command");
        ToolExecutionRequest newest = request("newest-call", "grep");
        List<ChatMessage> messages = List.of(
                UserMessage.from("调查问题"),
                AiMessage.from(first),
                ToolExecutionResultMessage.from(first, summary.toString()),
                AiMessage.from(newest),
                ToolExecutionResultMessage.from(newest, "newest result"));

        String placeholder = ((ToolExecutionResultMessage) projector
                .projectCurrentTurn(messages, false).get(2)).text();
        var projected = objectMapper.readTree(placeholder);

        assertThat(projected.path("evidenceId").asText()).isEqualTo(originalReference.evidenceId());
        assertThat(projected.path("sha256").asText()).isEqualTo(originalReference.sha256());
        assertThat(projected.path("originalChars").asInt()).isEqualTo(original.length());
        assertThat(projected.path("structure").path("fields").toString()).contains("queries");
        assertThat(evidenceStore.readAll(originalReference.evidenceId()).content()).isEqualTo(original);
        var catalog = objectMapper.readTree(evidenceStore.indexFile().toFile());
        assertThat(catalog.path("evidence")).hasSize(1);
        assertThat(catalog.path("evidence").get(0).path("observationCount").asInt()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"read_skill_file", "read_mcp_tool", "list_mcp_tools"})
    void archivesContractResultsAfterTheyHaveBeenConsumedOnce(String contractTool) throws Exception {
        Path state = tempDir.resolve("contract-state-" + contractTool);
        Files.createDirectories(state);
        LangChainEvidenceStore evidenceStore = new LangChainEvidenceStore(
                state.resolve("evidence"), new ObjectMapper());
        LangChainActiveToolResultProjector projector = new LangChainActiveToolResultProjector(
                "execution-contract", new CharacterTokenEstimator(), evidenceStore, 100);
        ToolExecutionRequest contract = request("contract-call", contractTool);
        ToolExecutionRequest newest = request("list-call", "run_skill_command");
        String contractContent = "# Project Hub\n\nproject-hub project-context --project-id value\n"
                + "x".repeat(2_000);
        List<ChatMessage> messages = List.of(
                UserMessage.from("调查问题"),
                AiMessage.from(contract),
                ToolExecutionResultMessage.from(contract, contractContent),
                AiMessage.from(newest),
                ToolExecutionResultMessage.from(newest, "project list"));

        String regular = ((ToolExecutionResultMessage) projector
                .projectCurrentTurn(messages, false).get(2)).text();
        String emergency = ((ToolExecutionResultMessage) projector
                .projectCurrentTurn(messages, true).get(2)).text();

        assertThat(regular).contains("langchain.active_tool_result_evidence", "readInstructions");
        assertThat(emergency).isEqualTo(regular);
    }

    @Test
    void preservesDeclaredEvidenceEnvelopeWhenItsReferenceIsInvalid() throws Exception {
        Files.createDirectories(tempDir.resolve("invalid-reference-state"));
        ObjectMapper objectMapper = new ObjectMapper();
        LangChainEvidenceStore evidenceStore = new LangChainEvidenceStore(
                tempDir.resolve("invalid-reference-state/evidence"), objectMapper);
        LangChainActiveToolResultProjector projector = new LangChainActiveToolResultProjector(
                "execution-invalid-reference", new CharacterTokenEstimator(), evidenceStore, 100);
        LangChainEvidenceStore.EvidenceReference reference = evidenceStore.record(
                "capability_command", "project-hub project-list", "original content");
        var invalid = evidenceStore.annotate(objectMapper.createObjectNode(), reference);
        invalid.put("sha256", "0".repeat(64));
        invalid.put("preview", "x".repeat(1_000));
        ToolExecutionRequest first = request("invalid-reference", "run_skill_command");
        ToolExecutionRequest newest = request("newest-call", "grep");
        List<ChatMessage> messages = List.of(
                UserMessage.from("调查问题"),
                AiMessage.from(first),
                ToolExecutionResultMessage.from(first, invalid.toString()),
                AiMessage.from(newest),
                ToolExecutionResultMessage.from(newest, "newest result"));

        ToolExecutionResultMessage projected = (ToolExecutionResultMessage) projector
                .projectCurrentTurn(messages, false).get(2);

        assertThat(projected.text()).isEqualTo(invalid.toString());
        var catalog = objectMapper.readTree(evidenceStore.indexFile().toFile());
        assertThat(catalog.path("evidence")).hasSize(1);
        assertThat(catalog.path("evidence").get(0).path("observationCount").asInt()).isEqualTo(1);
    }

    private ToolExecutionRequest request(String id) {
        return request(id, "read_file");
    }

    private ToolExecutionRequest request(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
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
            int tokens = 0;
            for (ChatMessage message : messages) {
                tokens += estimateTokenCountInMessage(message);
            }
            return tokens;
        }
    }
}
