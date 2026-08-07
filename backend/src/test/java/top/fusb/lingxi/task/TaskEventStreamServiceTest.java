package top.fusb.lingxi.task;

import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.dto.TaskEventPayload;
import top.fusb.lingxi.dto.TaskEventResponse;
import top.fusb.lingxi.dto.TaskExecutionEvent;
import top.fusb.lingxi.enums.TaskEventStatus;
import top.fusb.lingxi.enums.TaskEventType;
import top.fusb.lingxi.enums.TaskStatus;
import top.fusb.lingxi.repository.AgentTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskEventStreamServiceTest {

    @Test
    void replacesReconnectFromSamePageWithoutGrowingConnectionCount() {
        AgentTaskRepository taskRepository = mock(AgentTaskRepository.class);
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(17L);
        task.setStatus(TaskStatus.RUNNING);
        when(taskRepository.findById(17L)).thenReturn(Optional.of(task));
        TaskEventStreamService service = new TaskEventStreamService(mock(TaskEventService.class), taskRepository);

        service.stream(17L, 0L, false, false, "page-a");
        service.stream(17L, 0L, false, true, "page-a");

        Map<Long, Set<?>> connections = connections(service);
        AtomicInteger total = (AtomicInteger) ReflectionTestUtils.getField(service, "totalConnections");
        assertThat(connections.get(17L)).hasSize(1);
        assertThat(total).hasValue(1);

        service.stream(17L, 0L, false, false, "page-b");

        assertThat(connections.get(17L)).hasSize(2);
        assertThat(total).hasValue(2);
        service.complete(17L);
        assertThat(total).hasValue(0);
    }

    @Test
    void retainsCurrentTransientStateWithoutConnectionAndClearsItOnPersistentEvent() {
        TaskEventStreamService service = new TaskEventStreamService(
                mock(TaskEventService.class), mock(AgentTaskRepository.class));
        TaskExecutionEvent transientEvent = new TaskExecutionEvent();
        transientEvent.setType(TaskEventType.SYSTEM);
        transientEvent.setStatus(TaskEventStatus.RUNNING);
        transientEvent.setTitle("langchain.runtime.preparing");
        TaskEventPayload payload = new TaskEventPayload();
        payload.setTransientEvent(true);
        transientEvent.setPayload(payload);

        service.publishTransient(23L, transientEvent);

        assertThat(currentTransientEvents(service).get(23L).getTitle())
                .isEqualTo("langchain.runtime.preparing");

        service.publish(23L, new TaskEventResponse());

        assertThat(currentTransientEvents(service)).doesNotContainKey(23L);
    }

    @Test
    void clearsCurrentTransientStateWhenExecutionCompletes() {
        TaskEventStreamService service = new TaskEventStreamService(
                mock(TaskEventService.class), mock(AgentTaskRepository.class));
        TaskExecutionEvent transientEvent = new TaskExecutionEvent();
        transientEvent.setType(TaskEventType.THINKING);
        transientEvent.setStatus(TaskEventStatus.RUNNING);
        transientEvent.setTitle("langchain.reasoning");

        service.publishTransient(29L, transientEvent);
        service.complete(29L);

        assertThat(currentTransientEvents(service)).doesNotContainKey(29L);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Set<?>> connections(TaskEventStreamService service) {
        return (Map<Long, Set<?>>) ReflectionTestUtils.getField(service, "emittersByTaskId");
    }

    @SuppressWarnings("unchecked")
    private Map<Long, TaskEventResponse> currentTransientEvents(TaskEventStreamService service) {
        return (Map<Long, TaskEventResponse>) ReflectionTestUtils.getField(
                service, "currentTransientEventsByTaskId");
    }
}
