package top.fusb.lingxi.runtime.langchain.agent.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.runtime.langchain.agent.LangChainExecutionContext;
import top.fusb.lingxi.runtime.langchain.agent.memory.LangChainActiveToolResultProjector;
import top.fusb.lingxi.runtime.langchain.config.LangChainRuntimeProperties;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangChainEvidenceToolsTest {

    @TempDir
    Path tempDir;

    @Test
    void exposesSeparateReadAndQueryEvidenceContracts() {
        var specifications = ToolSpecifications.toolSpecificationsFrom(LangChainEvidenceTools.class);
        var readSpecification = specifications.stream()
                .filter(specification -> specification.name().equals("read_evidence"))
                .findFirst().orElseThrow();
        var querySpecification = specifications.stream()
                .filter(specification -> specification.name().equals("query_evidence"))
                .findFirst().orElseThrow();

        assertThat(specifications).hasSize(2);
        assertThat(readSpecification.parameters().properties()).containsOnlyKeys(
                "evidenceId", "offset", "maxChars");
        assertThat(readSpecification.parameters().required()).containsExactly("evidenceId");
        assertThat(readSpecification.description()).contains("分页", "query_evidence");
        assertThat(querySpecification.parameters().properties()).containsOnlyKeys(
                "evidenceId", "expression", "outputFormat");
        assertThat(querySpecification.parameters().required()).containsExactlyInAnyOrder(
                "evidenceId", "expression");
        assertThat(querySpecification.description()).contains("JMESPath", "不要传分页参数");
    }

    @Test
    void boundsCompleteResponseBelowActivePruneThreshold() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        LangChainRuntimeProperties properties = new LangChainRuntimeProperties();
        properties.setActiveToolResultMaxTokens(1_000);
        CharacterTokenEstimator tokenEstimator = new CharacterTokenEstimator();
        LangChainEvidenceStore evidenceStore = evidenceStore(objectMapper);
        LangChainEvidenceStore.EvidenceReference reference = evidenceStore.record(
                "read_file", "large.txt", "甲".repeat(20_000));
        LangChainEvidenceTools tools = evidenceTools(
                properties, evidenceStore, objectMapper, tokenEstimator);

        String response = tools.readEvidence(reference.evidenceId(), 0, 6_000);
        var responseJson = objectMapper.readTree(response);

        assertThat(response).hasSizeLessThanOrEqualTo(LangChainEvidenceTools.MAX_RESPONSE_CHARS);
        assertThat(tokenEstimator.estimateTokenCountInText(response)).isLessThanOrEqualTo(1_000);
        assertThat(responseJson.path("content").asText()).hasSizeLessThan(6_000);
        assertThat(responseJson.path("nextOffset").asInt())
                .isEqualTo(responseJson.path("endOffset").asInt());
    }

    @Test
    void boundedEvidenceReadDoesNotCreateAnotherArchiveProjection() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        LangChainRuntimeProperties properties = new LangChainRuntimeProperties();
        properties.setActiveToolResultMaxTokens(1_000);
        CharacterTokenEstimator tokenEstimator = new CharacterTokenEstimator();
        LangChainEvidenceStore evidenceStore = evidenceStore(objectMapper);
        LangChainEvidenceStore.EvidenceReference reference = evidenceStore.record(
                "read_file", "large.txt", "甲".repeat(20_000));
        LangChainEvidenceTools tools = evidenceTools(
                properties, evidenceStore, objectMapper, tokenEstimator);
        String response = tools.readEvidence(reference.evidenceId(), 0, 6_000);
        ToolExecutionRequest evidenceRequest = ToolExecutionRequest.builder()
                .id("evidence-call")
                .name("read_evidence")
                .arguments("{}")
                .build();
        ToolExecutionRequest newestRequest = ToolExecutionRequest.builder()
                .id("newest-call")
                .name("grep")
                .arguments("{}")
                .build();
        List<ChatMessage> messages = List.of(
                UserMessage.from("调查问题"),
                AiMessage.from(evidenceRequest),
                ToolExecutionResultMessage.from(evidenceRequest, response),
                AiMessage.from(newestRequest),
                ToolExecutionResultMessage.from(newestRequest, "最新结果"));
        LangChainActiveToolResultProjector projector = new LangChainActiveToolResultProjector(
                "execution", tokenEstimator, evidenceStore, properties.getActiveToolResultMaxTokens());

        List<ChatMessage> projected = projector.projectCurrentTurn(messages, false);

        assertThat(((ToolExecutionResultMessage) projected.get(2)).text()).isEqualTo(response);
        assertThat(objectMapper.readTree(evidenceStore.indexFile().toFile()).path("evidence")).hasSize(1);
    }

    @Test
    void usesMakaCompatiblePageDefaultsAndCapsOversizedRequests() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        LangChainRuntimeProperties properties = new LangChainRuntimeProperties();
        properties.setActiveToolResultMaxTokens(20_000);
        CharacterTokenEstimator tokenEstimator = new CharacterTokenEstimator();
        LangChainEvidenceStore evidenceStore = evidenceStore(objectMapper);
        LangChainEvidenceStore.EvidenceReference reference = evidenceStore.record(
                "read_file", "large.txt", "A".repeat(20_000));
        LangChainEvidenceTools tools = evidenceTools(
                properties, evidenceStore, objectMapper, tokenEstimator);

        var response = objectMapper.readTree(tools.readEvidence(reference.evidenceId(), 0, null));
        var cappedResponse = objectMapper.readTree(tools.readEvidence(reference.evidenceId(), 0, 15_000));

        assertThat(response.path("content").asText()).hasSize(LangChainEvidenceTools.DEFAULT_PAGE_CHARS);
        assertThat(cappedResponse.path("content").asText()).hasSize(LangChainEvidenceTools.MAX_PAGE_CHARS);
    }

    @Test
    void queriesJsonEvidenceThroughTheSameBoundedEntry() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        LangChainRuntimeProperties properties = new LangChainRuntimeProperties();
        properties.setActiveToolResultMaxTokens(20_000);
        LangChainEvidenceStore evidenceStore = evidenceStore(objectMapper);
        LangChainEvidenceStore.EvidenceReference reference = evidenceStore.record(
                "query_json", "items", "{\"items\":[{\"id\":1,\"status\":\"OK\"},{\"id\":2,\"status\":\"FAILED\"}]}");
        LangChainEvidenceTools tools = evidenceTools(
                properties, evidenceStore, objectMapper, new CharacterTokenEstimator());

        var response = objectMapper.readTree(tools.queryEvidence(
                reference.evidenceId(),
                "items[?status == 'FAILED'].{id:id,status:status}", "json"));

        assertThat(response.path("operation").asText()).isEqualTo("query");
        assertThat(response.path("result").get(0).path("id").asInt()).isEqualTo(2);
        assertThat(response.path("result").get(0).path("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void rendersObjectProjectionAsTsvRows() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        LangChainRuntimeProperties properties = new LangChainRuntimeProperties();
        properties.setActiveToolResultMaxTokens(20_000);
        LangChainEvidenceStore evidenceStore = evidenceStore(objectMapper);
        LangChainEvidenceStore.EvidenceReference reference = evidenceStore.record(
                "query_json", "messages", "[{\"time\":\"10:00\",\"message\":\"failed\"},"
                        + "{\"time\":\"10:01\",\"message\":\"recovered\"}]");
        LangChainEvidenceTools tools = evidenceTools(
                properties, evidenceStore, objectMapper, new CharacterTokenEstimator());

        var response = objectMapper.readTree(tools.queryEvidence(
                reference.evidenceId(),
                "[].{time: time, failed: contains(message, 'failed')}", "tsv"));

        assertThat(response.path("result").asText())
                .isEqualTo("10:00\ttrue\n10:01\tfalse");
    }

    @Test
    void asksForNarrowerQueryInsteadOfArchivingQueryResultsAgain() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        LangChainRuntimeProperties properties = new LangChainRuntimeProperties();
        properties.setActiveToolResultMaxTokens(500);
        LangChainEvidenceStore evidenceStore = evidenceStore(objectMapper);
        LangChainEvidenceStore.EvidenceReference reference = evidenceStore.record(
                "query_json", "items", objectMapper.writeValueAsString(java.util.Map.of(
                        "items", java.util.stream.IntStream.range(0, 1_000)
                                .mapToObj(index -> "item-" + index).toList())));
        LangChainEvidenceTools tools = evidenceTools(
                properties, evidenceStore, objectMapper, new CharacterTokenEstimator());

        var response = objectMapper.readTree(tools.queryEvidence(
                reference.evidenceId(), "items", null));

        assertThat(response.path("ok").asBoolean()).isFalse();
        assertThat(response.path("reason").asText()).isEqualTo("query_result_too_large");
        assertThat(response.path("operation").asText()).isEqualTo("query");
        assertThat(new CharacterTokenEstimator().estimateTokenCountInText(response.toString()))
                .isLessThanOrEqualTo(500);
        assertThat(objectMapper.readTree(evidenceStore.indexFile().toFile()).path("evidence")).hasSize(1);
    }

    @Test
    void usesTheDedicatedEvidenceBudgetInsteadOfTheGeneralToolBudget() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        LangChainRuntimeProperties properties = new LangChainRuntimeProperties();
        LangChainEvidenceStore evidenceStore = evidenceStore(objectMapper);
        LangChainEvidenceStore.EvidenceReference reference = evidenceStore.record(
                "read_file", "large.txt", "需要恢复的上下文");
        LangChainExecutionContext context = new LangChainExecutionContext(
                "execution-budget", tempDir, event -> { }, null, 1, 2, Long.MAX_VALUE);
        LangChainEvidenceTools tools = new LangChainEvidenceTools(
                properties, context, evidenceStore, objectMapper, new CharacterTokenEstimator());

        assertThat(tools.readEvidence(reference.evidenceId(), 0, 100))
                .contains("需要恢复的上下文");
        context.beginToolCall("read_file");

        assertThatThrownBy(() -> tools.readEvidence(reference.evidenceId(), 0, 100))
                .isInstanceOf(top.fusb.lingxi.runtime.langchain.agent.LangChainExecutionLimitException.class)
                .hasMessageContaining("Evidence 读取次数", "1");
    }

    private LangChainEvidenceStore evidenceStore(ObjectMapper objectMapper) throws Exception {
        Path evidenceDirectory = tempDir.resolve("evidence");
        Files.createDirectories(evidenceDirectory);
        return new LangChainEvidenceStore(evidenceDirectory, objectMapper);
    }

    private LangChainEvidenceTools evidenceTools(LangChainRuntimeProperties properties,
                                                 LangChainEvidenceStore evidenceStore,
                                                 ObjectMapper objectMapper,
                                                 TokenCountEstimator tokenEstimator) {
        LangChainExecutionContext context = new LangChainExecutionContext(
                "execution", tempDir, event -> { });
        return new LangChainEvidenceTools(
                properties, context, evidenceStore, objectMapper, tokenEstimator);
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
