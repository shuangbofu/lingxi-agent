package top.fusb.lingxi.runtime.codex.core;

import top.fusb.lingxi.runtime.api.event.RuntimeEventListener;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionRequest;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionResult;
import top.fusb.lingxi.runtime.api.model.RuntimeSessionRef;
import top.fusb.lingxi.runtime.api.model.RuntimeUsage;
import top.fusb.lingxi.runtime.api.model.RuntimeUsageQuery;
import top.fusb.lingxi.runtime.codex.config.CodexRuntimeProperties;
import top.fusb.lingxi.runtime.codex.execution.CodexExecutor;
import top.fusb.lingxi.runtime.codex.home.CodexHomeService;
import top.fusb.lingxi.runtime.codex.usage.CodexUsageSessionService;
import top.fusb.lingxi.runtime.codex.usage.CodexUsageSnapshot;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

@RequiredArgsConstructor
public class DefaultCodexRuntimeDelegate implements CodexRuntimeDelegate {

    private final CodexExecutor codexExecutor;
    private final CodexHomeService codexHomeService;
    private final CodexUsageSessionService tokenUsageSessionService;
    private final CodexRuntimeProperties codexProperties;

    @Override
    public RuntimeExecutionResult execute(RuntimeExecutionRequest request, RuntimeEventListener listener) {
        return codexExecutor.execute(request, listener);
    }

    @Override
    public boolean cancel(String executionId) {
        return codexExecutor.cancel(numericId(executionId, "executionId"));
    }

    @Override
    public long defaultTimeoutSeconds() {
        return codexProperties.getDefaultTimeoutSeconds();
    }

    @Override
    public Optional<RuntimeSessionRef> latestSession(String conversationId) {
        Long id = numericId(conversationId, "conversationId");
        return codexHomeService.latestSession(codexHomeService.conversationHome(id))
                .map(session -> new RuntimeSessionRef(session.sessionId(), session.sessionPath()));
    }

    @Override
    public Optional<RuntimeUsage> readUsage(RuntimeUsageQuery query) {
        Long executionId = numericId(query.executionId(), "executionId");
        Long conversationId = nullableNumericId(query.conversationId(), "conversationId");
        return tokenUsageSessionService.readTaskUsage(executionId, conversationId, query.startedAt(), query.endedAt())
                .map(this::toRuntimeUsage);
    }

    private RuntimeUsage toRuntimeUsage(CodexUsageSnapshot usage) {
        return new RuntimeUsage(
                usage.getEventTimestamp(), usage.getRequestCount(), usage.getInputTokens(),
                usage.getCachedInputTokens(), usage.getCacheCreationInputTokens(), usage.getOutputTokens(),
                usage.getReasoningOutputTokens(), usage.getTotalTokens(), usage.getLastInputTokens(),
                usage.getLastCachedInputTokens(), usage.getLastCacheCreationInputTokens(), usage.getLastOutputTokens(),
                usage.getLastReasoningOutputTokens(), usage.getLastTotalTokens(), usage.getModelContextWindow()
        );
    }

    private Long numericId(String value, String fieldName) {
        Long id = nullableNumericId(value, fieldName);
        if (id == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return id;
    }

    private Long nullableNumericId(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(fieldName + " must be numeric", exception);
        }
    }
}
