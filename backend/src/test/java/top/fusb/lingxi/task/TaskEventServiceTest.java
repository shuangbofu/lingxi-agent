package top.fusb.lingxi.task;

import top.fusb.lingxi.dto.TaskEventPayload;
import top.fusb.lingxi.dto.TaskEventResponse;
import top.fusb.lingxi.dto.TaskExecutionEvent;
import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.entity.TaskEventEntity;
import top.fusb.lingxi.enums.TaskEventStatus;
import top.fusb.lingxi.enums.TaskEventType;
import top.fusb.lingxi.repository.AgentTaskRepository;
import top.fusb.lingxi.repository.TaskEventRepository;
import top.fusb.lingxi.runtime.api.event.RuntimeActionIcon;
import top.fusb.lingxi.runtime.api.event.RuntimeEventSemantic;
import top.fusb.lingxi.runtime.api.event.RuntimeEventVisibility;
import top.fusb.lingxi.runtime.api.event.RuntimeModelTimingMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class TaskEventServiceTest {

    @Test
    void preservesUnifiedRuntimeContractWhenMaskingProcessOutput() {
        TaskEventService service = new TaskEventService(
                mock(AgentTaskRepository.class),
                mock(TaskEventRepository.class),
                mock(TaskEventContentService.class));
        TaskEventPayload payload = new TaskEventPayload();
        payload.setArguments("sensitive arguments");
        payload.setOutput("sensitive output");
        payload.setSemantic(RuntimeEventSemantic.MODEL_REQUEST_COMPLETED);
        payload.setVisibility(RuntimeEventVisibility.INTERNAL);
        payload.setModelTimingMode(RuntimeModelTimingMode.STREAMING);
        payload.setActionIcon(RuntimeActionIcon.FILE_TEXT);
        TaskEventResponse response = new TaskEventResponse();
        response.setPayload(payload);

        TaskEventPayload masked = service.maskProcessOutput(response).getPayload();

        assertThat(masked.getArguments()).isEqualTo("输入内容已隐藏");
        assertThat(masked.getOutput()).isEqualTo("输出内容已隐藏");
        assertThat(masked.getSemantic()).isEqualTo(RuntimeEventSemantic.MODEL_REQUEST_COMPLETED);
        assertThat(masked.getVisibility()).isEqualTo(RuntimeEventVisibility.INTERNAL);
        assertThat(masked.getModelTimingMode()).isEqualTo(RuntimeModelTimingMode.STREAMING);
        assertThat(masked.getActionIcon()).isEqualTo(RuntimeActionIcon.FILE_TEXT);
    }

    @Test
    void preservesActionIconWhenPersistingCompactedPayload() {
        AgentTaskRepository taskRepository = mock(AgentTaskRepository.class);
        TaskEventRepository eventRepository = mock(TaskEventRepository.class);
        TaskEventContentService contentService = mock(TaskEventContentService.class);
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(12L);
        when(taskRepository.getReferenceById(12L)).thenReturn(task);
        when(eventRepository.saveAndFlush(any(TaskEventEntity.class))).thenAnswer(invocation -> {
            TaskEventEntity entity = invocation.getArgument(0);
            entity.setId(34L);
            return entity;
        });
        when(eventRepository.save(any(TaskEventEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(contentService.compact(anyLong(), anyLong(), anyString(), any())).thenAnswer(invocation -> invocation.getArgument(3));
        TaskEventService service = new TaskEventService(taskRepository, eventRepository, contentService);
        TaskEventPayload payload = new TaskEventPayload();
        payload.setActionIcon(RuntimeActionIcon.MAGNIFYING_GLASS);
        TaskExecutionEvent event = new TaskExecutionEvent();
        event.setType(TaskEventType.COMMAND);
        event.setStatus(TaskEventStatus.SUCCESS);
        event.setTitle("搜索文件内容");
        event.setPayload(payload);

        TaskEventResponse saved = service.save(12L, event);

        assertThat(saved.getPayload().getActionIcon()).isEqualTo(RuntimeActionIcon.MAGNIFYING_GLASS);
    }

    @Test
    void removesReasoningContentWhenMaskingProcessOutput() {
        TaskEventService service = new TaskEventService(
                mock(AgentTaskRepository.class),
                mock(TaskEventRepository.class),
                mock(TaskEventContentService.class));
        TaskEventPayload payload = new TaskEventPayload();
        payload.setMessage("包含代码的模型思考");
        payload.setOutput("class InternalCode {}");
        payload.setActionTarget("src/InternalCode.java");
        payload.setMessageFile(true);
        payload.setOutputFile(true);
        TaskEventResponse response = new TaskEventResponse();
        response.setType(TaskEventType.THINKING.name());
        response.setDetail("先读取 src/InternalCode.java 再分析代码");
        response.setDetailFile(true);
        response.setPayload(payload);

        TaskEventResponse masked = service.maskProcessOutput(response);

        assertThat(masked.getDetail()).isNull();
        assertThat(masked.getDetailFile()).isFalse();
        assertThat(masked.getPayload().getMessage()).isNull();
        assertThat(masked.getPayload().getOutput()).isNull();
        assertThat(masked.getPayload().getActionTarget()).isNull();
        assertThat(masked.getPayload().getMessageFile()).isFalse();
        assertThat(masked.getPayload().getOutputFile()).isFalse();
    }
}
