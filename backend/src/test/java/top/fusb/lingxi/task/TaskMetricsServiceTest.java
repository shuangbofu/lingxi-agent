package top.fusb.lingxi.task;

import top.fusb.lingxi.dto.TaskEventPayload;
import top.fusb.lingxi.dto.TaskExecutionMetricsResponse;
import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.entity.TaskEventEntity;
import top.fusb.lingxi.enums.TaskEventStatus;
import top.fusb.lingxi.enums.TaskEventType;
import top.fusb.lingxi.repository.TaskEventRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskMetricsServiceTest {

    @Test
    void shouldCalculateProtocolLevelExecutionMetricsWithoutCapabilityNames() {
        TaskEventRepository repository = mock(TaskEventRepository.class);
        TaskMetricsService service = new TaskMetricsService(repository);
        LocalDateTime start = LocalDateTime.parse("2026-07-22T09:00:00");
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(12L);
        task.setCreatedAt(start.minusSeconds(1));
        task.setStartedAt(start);
        task.setEngineCompletedAt(start.plusSeconds(9));
        task.setEndedAt(start.plusSeconds(12));
        List<TaskEventEntity> events = List.of(
                event(task, start.plusSeconds(1), TaskEventType.SYSTEM, TaskEventStatus.INFO,
                        "codex.turn.started", payload("turn.started", null, null, null)),
                event(task, start.plusSeconds(2), TaskEventType.COMMAND, TaskEventStatus.RUNNING,
                        "capability:search", payload("command_execution", "call-1", "capability:custom:search", "custom search --query x")),
                event(task, start.plusSeconds(4), TaskEventType.COMMAND, TaskEventStatus.SUCCESS,
                        "capability:search", payload("item.completed", "call-1", "capability:custom:search", "custom search --query x")),
                event(task, start.plusSeconds(5), TaskEventType.COMMAND, TaskEventStatus.RUNNING,
                        "capability:search", payload("command_execution", "call-2", "capability:custom:search", "custom search --query x")),
                event(task, start.plusSeconds(6), TaskEventType.COMMAND, TaskEventStatus.SUCCESS,
                        "capability:search", payload("item.completed", "call-2", "capability:custom:search", "custom search --query x")),
                event(task, start.plusSeconds(7), TaskEventType.SYSTEM, TaskEventStatus.INFO,
                        "上下文已压缩", compactionPayload())
        );
        when(repository.findByTask_IdInOrderByTask_IdAscCreatedAtAsc(anySet())).thenReturn(events);

        TaskExecutionMetricsResponse metrics = service.captureExecutionMetrics(task);

        assertThat(metrics.getTotalDurationMs()).isEqualTo(12_000L);
        assertThat(metrics.getFirstFeedbackMs()).isEqualTo(2_000L);
        assertThat(metrics.getCommandDurationMs()).isEqualTo(3_000L);
        assertThat(metrics.getResultProcessingMs()).isEqualTo(3_000L);
        assertThat(metrics.getCompactionCount()).isEqualTo(1L);
        assertThat(metrics.getDuplicateCapabilityCallCount()).isEqualTo(1L);

        TaskExecutionMetricsResponse stored = service.storedExecutionMetrics(task);
        assertThat(stored.getFirstFeedbackMs()).isEqualTo(2_000L);
        assertThat(stored.getCommandDurationMs()).isEqualTo(3_000L);
        assertThat(stored.getResultProcessingMs()).isEqualTo(3_000L);
        assertThat(stored.getCompactionCount()).isEqualTo(1L);
        assertThat(stored.getDuplicateCapabilityCallCount()).isEqualTo(1L);
    }

    @Test
    void shouldCloseRunningCommandAtTaskEnd() {
        TaskEventRepository repository = mock(TaskEventRepository.class);
        TaskMetricsService service = new TaskMetricsService(repository);
        LocalDateTime start = LocalDateTime.parse("2026-07-24T10:00:00");
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(529L);
        task.setCreatedAt(start.minusSeconds(1));
        task.setStartedAt(start);
        task.setEndedAt(start.plusSeconds(12));
        List<TaskEventEntity> events = List.of(
                event(task, start.plusSeconds(2), TaskEventType.AGENT_MESSAGE, TaskEventStatus.INFO,
                        "开始处理", payload("agent_message", null, null, null)),
                event(task, start.plusSeconds(4), TaskEventType.COMMAND, TaskEventStatus.RUNNING,
                        "capability:test:run", payload("command_execution", "call-running", "capability:test:run", "test run"))
        );
        when(repository.findByTask_IdInOrderByTask_IdAscCreatedAtAsc(anySet())).thenReturn(events);

        TaskExecutionMetricsResponse metrics = service.captureExecutionMetrics(task);

        assertThat(metrics.getFirstFeedbackMs()).isEqualTo(2_000L);
        assertThat(metrics.getCommandDurationMs()).isEqualTo(8_000L);
    }

    @Test
    void shouldRecalculateHistoricalTerminalTaskWithoutStoredMetrics() {
        TaskEventRepository repository = mock(TaskEventRepository.class);
        TaskMetricsService service = new TaskMetricsService(repository);
        LocalDateTime start = LocalDateTime.parse("2026-07-24T10:00:00");
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(529L);
        task.setCreatedAt(start.minusSeconds(1));
        task.setStartedAt(start);
        task.setEndedAt(start.plusSeconds(10));
        List<TaskEventEntity> events = List.of(
                event(task, start.plusSeconds(1), TaskEventType.AGENT_MESSAGE, TaskEventStatus.INFO,
                        "开始处理", payload("agent_message", null, null, null)),
                event(task, start.plusSeconds(2), TaskEventType.COMMAND, TaskEventStatus.RUNNING,
                        "command:test", payload("command_execution", "call-1", "command:test", "test")),
                event(task, start.plusSeconds(5), TaskEventType.COMMAND, TaskEventStatus.SUCCESS,
                        "command:test", payload("item.completed", "call-1", "command:test", "test"))
        );
        when(repository.findByTask_IdInOrderByTask_IdAscCreatedAtAsc(anySet())).thenReturn(events);

        TaskExecutionMetricsResponse metrics = service.resolvedExecutionMetrics(task);

        assertThat(metrics.getTotalDurationMs()).isEqualTo(10_000L);
        assertThat(metrics.getFirstFeedbackMs()).isEqualTo(1_000L);
        assertThat(metrics.getCommandDurationMs()).isEqualTo(3_000L);
    }

    private TaskEventEntity event(AgentTaskEntity task, LocalDateTime createdAt, TaskEventType type,
                                  TaskEventStatus status, String title, TaskEventPayload payload) {
        return event(task, createdAt, type, status, title, null, payload);
    }

    private TaskEventEntity event(AgentTaskEntity task, LocalDateTime createdAt, TaskEventType type,
                                  TaskEventStatus status, String title, String detail, TaskEventPayload payload) {
        TaskEventEntity event = new TaskEventEntity();
        event.setTask(task);
        event.setType(type.name());
        event.setStatus(status);
        event.setTitle(title);
        event.setDetail(detail);
        event.setPayload(payload);
        event.setCreatedAt(createdAt);
        return event;
    }

    private TaskEventPayload payload(String rawType, String instanceId, String actionKey, String target) {
        TaskEventPayload payload = new TaskEventPayload();
        payload.setRawType(rawType);
        payload.setActionInstanceId(instanceId);
        payload.setActionKey(actionKey);
        payload.setActionTarget(target);
        return payload;
    }

    private TaskEventPayload compactionPayload() {
        TaskEventPayload payload = new TaskEventPayload();
        payload.setSemantic(top.fusb.lingxi.runtime.api.event.RuntimeEventSemantic.CONTEXT_COMPACTION);
        return payload;
    }
}
