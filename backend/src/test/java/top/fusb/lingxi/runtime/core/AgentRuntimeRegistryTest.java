package top.fusb.lingxi.runtime.core;

import top.fusb.lingxi.runtime.api.AgentRuntime;
import top.fusb.lingxi.runtime.api.event.RuntimeEventListener;
import top.fusb.lingxi.runtime.api.model.RuntimeDescriptor;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionRequest;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRuntimeRegistryTest {

    @Test
    void shouldResolveRuntimeByNormalizedCode() {
        AgentRuntime runtime = runtime("codex");
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry(List.of(runtime));

        assertEquals(runtime, registry.require(" CODEX "));
        assertEquals(List.of("codex"), registry.codes());
        assertEquals(List.of(new RuntimeDescriptor("codex", "codex", null)), registry.descriptors());
    }

    @Test
    void shouldRejectDuplicateRuntimeCode() {
        assertThrows(IllegalStateException.class,
                () -> new AgentRuntimeRegistry(List.of(runtime("codex"), runtime("CODEX"))));
    }

    private AgentRuntime runtime(String code) {
        return new AgentRuntime() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public RuntimeExecutionResult execute(RuntimeExecutionRequest request, RuntimeEventListener listener) {
                return null;
            }

            @Override
            public boolean cancel(String executionId) {
                return false;
            }

        };
    }
}
