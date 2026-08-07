package top.fusb.lingxi.runtime.langchain.agent.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangChainEvidenceStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void storesContentByHashAndRecordsRepeatedObservations() throws Exception {
        Path evidenceDirectory = tempDir.resolve("runtime-state/evidence");
        Files.createDirectories(evidenceDirectory.getParent());
        LangChainEvidenceStore store = new LangChainEvidenceStore(evidenceDirectory, new ObjectMapper());

        LangChainEvidenceStore.EvidenceReference first = store.record(
                "query", "password=secret", "same-result");
        LangChainEvidenceStore.EvidenceReference second = store.record(
                "query", "password=secret", "same-result");

        assertThat(first.duplicate()).isFalse();
        assertThat(second.duplicate()).isTrue();
        assertThat(second.evidenceId()).isEqualTo(first.evidenceId());
        assertThat(second.observationCount()).isEqualTo(2);
        assertThat(evidenceDirectory.resolve(first.outputFile())).hasContent("same-result");
        var modelReference = store.annotate(new ObjectMapper().createObjectNode(), first);
        assertThat(modelReference.path("evidenceId").asText()).isEqualTo(first.evidenceId());
        assertThat(modelReference.path("kind").asText()).isEqualTo("langchain.evidence_reference");
        assertThat(modelReference.path("readInstructions").asText())
                .contains("read_evidence", "query_evidence", "recommendedOperation");
        assertThat(modelReference.has("fileRoot")).isFalse();
        assertThat(modelReference.has("outputFile")).isFalse();
        var index = new ObjectMapper().readTree(store.indexFile().toFile());
        assertThat(index.path("evidence")).hasSize(1);
        assertThat(index.path("evidence").get(0).path("firstSource").asText()).contains("password=****");
    }

    @Test
    void resolvesOnlyMarkedAnnotationsBackedByTheCurrentStore() throws Exception {
        Path evidenceDirectory = tempDir.resolve("annotated-evidence");
        Files.createDirectories(evidenceDirectory);
        ObjectMapper objectMapper = new ObjectMapper();
        LangChainEvidenceStore store = new LangChainEvidenceStore(evidenceDirectory, objectMapper);
        LangChainEvidenceStore.EvidenceReference reference = store.record(
                "query", "items", "{\"items\":[1,2,3]}");
        var annotation = store.annotate(objectMapper.createObjectNode(), reference);
        var mismatchedHash = annotation.deepCopy().put("sha256", "0".repeat(64));
        var mismatchedId = annotation.deepCopy().put("evidenceId", "ev-0000000000000000");
        var mismatchedLength = annotation.deepCopy().put("originalChars", reference.originalChars() + 1);
        var invalidFieldType = annotation.deepCopy();
        invalidFieldType.putObject("sha256");
        var unmarked = annotation.deepCopy();
        unmarked.remove("kind");

        LangChainEvidenceStore.ResolvedEvidence resolved = store
                .resolveAnnotatedReference(annotation.toString()).orElseThrow();

        assertThat(resolved.reference().evidenceId()).isEqualTo(reference.evidenceId());
        assertThat(resolved.reference().duplicate()).isFalse();
        assertThat(resolved.content()).isEqualTo("{\"items\":[1,2,3]}");
        assertThat(store.resolveAnnotatedReference(unmarked.toString())).isEmpty();
        assertThat(store.resolveAnnotatedReference("ordinary output")).isEmpty();
        assertThatThrownBy(() -> store.resolveAnnotatedReference(mismatchedHash.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Evidence 引用与当前任务证据索引不匹配");
        assertThatThrownBy(() -> store.resolveAnnotatedReference(mismatchedId.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Evidence 引用与当前任务证据索引不匹配");
        assertThatThrownBy(() -> store.resolveAnnotatedReference(mismatchedLength.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Evidence 引用与当前任务证据索引不匹配");
        assertThatThrownBy(() -> store.resolveAnnotatedReference(invalidFieldType.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Evidence 引用字段无效");
        var index = objectMapper.readTree(store.indexFile().toFile());
        assertThat(index.path("evidence").get(0).path("observationCount").asInt()).isEqualTo(1);
    }

    @Test
    void rejectsCorruptedContentBehindAMarkedReference() throws Exception {
        Path evidenceDirectory = tempDir.resolve("corrupted-reference");
        Files.createDirectories(evidenceDirectory);
        ObjectMapper objectMapper = new ObjectMapper();
        LangChainEvidenceStore store = new LangChainEvidenceStore(evidenceDirectory, objectMapper);
        LangChainEvidenceStore.EvidenceReference reference = store.record(
                "query", "items", "original content");
        var annotation = store.annotate(objectMapper.createObjectNode(), reference);
        Files.writeString(evidenceDirectory.resolve(reference.outputFile()), "corrupted content");

        assertThatThrownBy(() -> store.resolveAnnotatedReference(annotation.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("上下文证据内容校验失败");
    }

    @Test
    void activePlaceholderRequiresContentMatchingItsReference() throws Exception {
        Path evidenceDirectory = tempDir.resolve("placeholder-validation");
        Files.createDirectories(evidenceDirectory);
        LangChainEvidenceStore store = new LangChainEvidenceStore(evidenceDirectory, new ObjectMapper());
        LangChainEvidenceStore.EvidenceReference reference = store.record(
                "query", "items", "original content");

        assertThatThrownBy(() -> store.activeResultPlaceholder(
                reference, "call-1", "query", 10, "different content"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("正文与证据引用不匹配");
    }

    @Test
    void restoresCatalogAndEvidenceFilesIntoAnotherWorkspace() throws Exception {
        Path sourceWorkspace = tempDir.resolve("source");
        Path targetWorkspace = tempDir.resolve("target");
        Files.createDirectories(sourceWorkspace);
        Files.createDirectories(targetWorkspace);
        LangChainEvidenceStore source = new LangChainEvidenceStore(sourceWorkspace, new ObjectMapper());
        LangChainEvidenceStore.EvidenceReference original = source.record(
                "read_file", "report.txt", "recoverable-result");
        LangChainEvidenceStore target = new LangChainEvidenceStore(targetWorkspace, new ObjectMapper());

        int restored = target.restoreFrom(source.indexFile());
        LangChainEvidenceStore.EvidenceReference repeated = target.record(
                "read_file", "report.txt", "recoverable-result");

        assertThat(restored).isEqualTo(1);
        assertThat(repeated.duplicate()).isTrue();
        assertThat(repeated.evidenceId()).isEqualTo(original.evidenceId());
        assertThat(targetWorkspace.resolve(repeated.outputFile())).hasContent("recoverable-result");
    }

    @Test
    void resolvesEvidenceIndexFromSessionMemoryPath() {
        Path memoryFile = tempDir.resolve("workspace/backend-runtime/langchain-state/chat-memory.json");

        Path index = LangChainEvidenceStore.indexFileForMemory(memoryFile);

        assertThat(index).isEqualTo(
                tempDir.resolve("workspace/backend-runtime/langchain-state/evidence/index.json"));
    }

    @Test
    void readsArchivedEvidenceByCharacterOffset() throws Exception {
        Path evidenceDirectory = tempDir.resolve("paged-evidence");
        Files.createDirectories(evidenceDirectory);
        LangChainEvidenceStore store = new LangChainEvidenceStore(evidenceDirectory, new ObjectMapper());
        LangChainEvidenceStore.EvidenceReference reference = store.record(
                "read_file", "report.txt", "第一段-第二段-第三段");

        LangChainEvidenceStore.EvidenceSlice first = store.read(reference.evidenceId(), 0, 4);
        LangChainEvidenceStore.EvidenceSlice second = store.read(
                reference.evidenceId(), first.nextOffset(), 100);

        assertThat(first.content()).isEqualTo("第一段-");
        assertThat(first.nextOffset()).isEqualTo(4);
        assertThat(first.content() + second.content()).isEqualTo("第一段-第二段-第三段");
        assertThat(second.nextOffset()).isNull();
    }

    @Test
    void rejectsInvalidCatalogBeforeReplacingCurrentEvidence() throws Exception {
        Path sourceWorkspace = tempDir.resolve("invalid-source");
        Path targetWorkspace = tempDir.resolve("preserved-target");
        Files.createDirectories(sourceWorkspace);
        Files.createDirectories(targetWorkspace);
        LangChainEvidenceStore source = new LangChainEvidenceStore(sourceWorkspace, new ObjectMapper());
        LangChainEvidenceStore.EvidenceReference sourceEvidence = source.record(
                "read_file", "source.txt", "source-result");
        Files.writeString(sourceWorkspace.resolve(sourceEvidence.outputFile()), "corrupted-result");
        LangChainEvidenceStore target = new LangChainEvidenceStore(targetWorkspace, new ObjectMapper());
        LangChainEvidenceStore.EvidenceReference targetEvidence = target.record(
                "read_file", "target.txt", "target-result");

        assertThatThrownBy(() -> target.restoreFrom(source.indexFile()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("恢复 LangChain 上下文证据失败");
        LangChainEvidenceStore.EvidenceReference repeated = target.record(
                "read_file", "target.txt", "target-result");
        assertThat(repeated.duplicate()).isTrue();
        assertThat(repeated.evidenceId()).isEqualTo(targetEvidence.evidenceId());
    }
}
