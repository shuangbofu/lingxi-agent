package top.fusb.lingxi.runtime.langchain.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LangChainSkillAccessStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPersistAndRestoreInspectedSkillsAcrossSessions() {
        Path sourceMemory = tempDir.resolve("source/chat-memory.json");
        Path targetMemory = tempDir.resolve("target/chat-memory.json");
        LangChainSkillAccessStateStore source = new LangChainSkillAccessStateStore(
                LangChainSkillAccessStateStore.stateFileForMemory(sourceMemory), new ObjectMapper());
        LangChainSkillAccessStateStore target = new LangChainSkillAccessStateStore(
                LangChainSkillAccessStateStore.stateFileForMemory(targetMemory), new ObjectMapper());
        source.write(Set.of("project-hub", "code-repository"));

        boolean restored = target.restoreFrom(
                LangChainSkillAccessStateStore.stateFileForMemory(sourceMemory));

        assertThat(restored).isTrue();
        assertThat(target.read()).containsExactlyInAnyOrder("project-hub", "code-repository");
        target.clear();
        assertThat(target.read()).isEmpty();
    }
}
