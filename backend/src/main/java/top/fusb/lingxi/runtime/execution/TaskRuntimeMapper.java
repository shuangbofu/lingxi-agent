package top.fusb.lingxi.runtime.execution;

import top.fusb.lingxi.dto.TaskEventPayload;
import top.fusb.lingxi.dto.TaskExecutionEvent;
import top.fusb.lingxi.dto.TokenUsageSnapshot;
import top.fusb.lingxi.enums.TaskEventStatus;
import top.fusb.lingxi.enums.TaskEventType;
import top.fusb.lingxi.runtime.api.event.RuntimeEvent;
import top.fusb.lingxi.runtime.api.event.RuntimeEventPayload;
import top.fusb.lingxi.runtime.api.model.RuntimeUsage;

public final class TaskRuntimeMapper {

    private TaskRuntimeMapper() {
    }

    public static TaskExecutionEvent toTaskEvent(RuntimeEvent event) {
        if (event == null) {
            return null;
        }
        TaskExecutionEvent target = new TaskExecutionEvent();
        target.setType(TaskEventType.valueOf(event.type().name()));
        target.setStatus(TaskEventStatus.valueOf(event.status().name()));
        target.setTitle(event.title());
        target.setDetail(event.detail());
        target.setPayload(toTaskPayload(event.payload()));
        return target;
    }

    public static TokenUsageSnapshot toTokenUsage(RuntimeUsage usage) {
        if (usage == null) {
            return null;
        }
        TokenUsageSnapshot target = new TokenUsageSnapshot();
        target.setEventTimestamp(usage.eventTimestamp());
        target.setRequestCount(usage.requestCount());
        target.setInputTokens(usage.inputTokens());
        target.setCachedInputTokens(usage.cachedInputTokens());
        target.setCacheCreationInputTokens(usage.cacheCreationInputTokens());
        target.setOutputTokens(usage.outputTokens());
        target.setReasoningOutputTokens(usage.reasoningOutputTokens());
        target.setTotalTokens(usage.totalTokens());
        target.setLastInputTokens(usage.lastInputTokens());
        target.setLastCachedInputTokens(usage.lastCachedInputTokens());
        target.setLastCacheCreationInputTokens(usage.lastCacheCreationInputTokens());
        target.setLastOutputTokens(usage.lastOutputTokens());
        target.setLastReasoningOutputTokens(usage.lastReasoningOutputTokens());
        target.setLastTotalTokens(usage.lastTotalTokens());
        target.setModelContextWindow(usage.modelContextWindow());
        return target;
    }

    private static TaskEventPayload toTaskPayload(RuntimeEventPayload payload) {
        if (payload == null) {
            return null;
        }
        TaskEventPayload target = new TaskEventPayload();
        target.setRawType(payload.rawType());
        target.setItemType(payload.itemType());
        target.setItemId(payload.itemId());
        target.setStatus(payload.status());
        target.setCommand(payload.command());
        target.setToolName(payload.toolName());
        target.setCallId(payload.callId());
        target.setArguments(payload.arguments());
        target.setOutput(payload.output());
        target.setMessage(payload.message());
        target.setExitCode(payload.exitCode());
        target.setActionKey(payload.actionKey());
        target.setActionInstanceId(payload.actionInstanceId());
        target.setActionLabel(payload.actionLabel());
        target.setActionTarget(payload.actionTarget());
        target.setTransientEvent(payload.transientEvent());
        target.setMetrics(payload.metrics());
        target.setActionGroupId(payload.actionGroupId());
        target.setActionGroupSize(payload.actionGroupSize());
        target.setSemantic(payload.semantic());
        target.setVisibility(payload.visibility());
        target.setModelTimingMode(payload.modelTimingMode());
        target.setActionIcon(payload.actionIcon());
        return target;
    }
}
