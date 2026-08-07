package top.fusb.lingxi.task;

import top.fusb.lingxi.dto.TaskEventResponse;
import top.fusb.lingxi.dto.TaskEventEntryResponse;
import top.fusb.lingxi.dto.TaskExecutionEvent;
import top.fusb.lingxi.dto.TaskEventPayload;
import top.fusb.lingxi.dto.TaskInteractionOption;
import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.entity.TaskEventEntity;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.enums.TaskEventStatus;
import top.fusb.lingxi.enums.TaskEventType;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.kit.TextKit;
import top.fusb.lingxi.repository.AgentTaskRepository;
import top.fusb.lingxi.repository.TaskEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TaskEventService {

    private static final int MAX_EVENT_TEXT_LENGTH = 2_000;
    private static final int MAX_EVENT_TITLE_LENGTH = 500;
    private static final String HIDDEN_PROCESS_OUTPUT_TEXT = "输出内容已隐藏";
    private static final String HIDDEN_PROCESS_ARGUMENTS_TEXT = "输入内容已隐藏";

    private final AgentTaskRepository agentTaskRepository;
    private final TaskEventRepository taskEventRepository;
    private final TaskEventContentService taskEventContentService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TaskEventResponse save(Long taskId, TaskExecutionEvent event) {
        if (event == null || event.getTitle() == null || event.getTitle().isBlank()) {
            return null;
        }
        AgentTaskEntity task = agentTaskRepository.getReferenceById(taskId);
        TaskEventEntity entity = new TaskEventEntity();
        entity.setTask(task);
        entity.setType(event.getType().name());
        entity.setStatus(event.getStatus());
        entity.setTitle(TextKit.limit(event.getTitle(), MAX_EVENT_TITLE_LENGTH));
        entity.setDetail(null);
        entity.setPayload(null);
        entity.setCreatedAt(LocalDateTime.now());
        TaskEventEntity saved = taskEventRepository.saveAndFlush(entity);
        saved.setDetail(compactText(taskId, saved.getId(), "detail", event.getDetail()));
        saved.setPayload(compactPayload(taskId, saved.getId(), event.getPayload()));
        return toResponse(taskEventRepository.save(saved));
    }

    public List<TaskEventResponse> list(Long taskId) {
        return list(taskId, true);
    }

    public List<TaskEventResponse> list(Long taskId, boolean includeProcessOutput) {
        return taskEventRepository.findTop200ByTaskIdAndTypeNotOrderByCreatedAtAsc(taskId, TaskEventType.METRIC.name()).stream()
                .map(this::toResponse)
                .map(response -> includeProcessOutput ? response : withoutProcessOutput(response))
                .toList();
    }

    public List<TaskEventResponse> listAfter(Long taskId, Long afterId) {
        return listAfter(taskId, afterId, true);
    }

    public List<TaskEventResponse> listAfter(Long taskId, Long afterId, boolean includeProcessOutput) {
        return taskEventRepository.findTop200ByTaskIdAndIdGreaterThanAndTypeNotOrderByCreatedAtAsc(
                        taskId, afterId == null ? 0L : afterId, TaskEventType.METRIC.name()).stream()
                .map(this::toResponse)
                .map(response -> includeProcessOutput ? response : withoutProcessOutput(response))
                .toList();
    }

    public List<TaskEventEntryResponse> listEntries(Long taskId, boolean running) {
        return List.of();
    }

    public void requireEventBelongsToTask(Long taskId, Long eventId) {
        if (!taskEventRepository.existsByIdAndTaskId(eventId, taskId)) {
            throw new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.TASK_NOT_FOUND, "任务事件不存在");
        }
    }

    /**
     * 清除一次失败执行产生的事件记录及文件化事件内容。
     *
     * @param taskId 任务 ID
     * @return 无返回值
     */
    @Transactional
    public void clear(Long taskId) {
        taskEventContentService.clearTask(taskId);
        taskEventRepository.deleteByTaskId(taskId);
    }

    public TaskEventResponse maskProcessOutput(TaskEventResponse response) {
        return withoutProcessOutput(response);
    }

    private TaskEventResponse toResponse(TaskEventEntity entity) {
        TaskEventResponse response = new TaskEventResponse();
        response.setId(entity.getId());
        response.setType(TaskEventType.parse(entity.getType()).name());
        response.setStatus(entity.getStatus().name());
        response.setTitle(entity.getTitle());
        TaskEventPayload payload = normalizeResponsePayload(entity);
        response.setDetail(responseDetail(entity, payload));
        response.setDetailFile(taskEventContentService.exists(entity.getTask().getId(), entity.getId(), "detail"));
        response.setPayload(payload);
        response.setCreatedAt(entity.getCreatedAt());
        return response;
    }

    private TaskEventResponse withoutProcessOutput(TaskEventResponse source) {
        TaskEventResponse response = new TaskEventResponse();
        response.setId(source.getId());
        response.setType(source.getType());
        response.setStatus(source.getStatus());
        response.setTitle(source.getTitle());
        response.setCreatedAt(source.getCreatedAt());
        TaskEventPayload payload = source.getPayload();
        boolean reasoning = TaskEventType.THINKING.name().equals(source.getType());
        response.setDetail(reasoning ? null : source.getDetail());
        response.setDetailFile(false);
        TaskEventPayload maskedPayload = withoutProcessOutput(payload);
        if (reasoning && maskedPayload != null) {
            maskedPayload.setCommand(null);
            maskedPayload.setArguments(null);
            maskedPayload.setOutput(null);
            maskedPayload.setMessage(null);
            maskedPayload.setActionTarget(null);
            maskedPayload.setCommandFile(false);
            maskedPayload.setArgumentsFile(false);
            maskedPayload.setOutputFile(false);
            maskedPayload.setMessageFile(false);
            maskedPayload.setActionTargetFile(false);
        }
        response.setPayload(maskedPayload);
        return response;
    }

    private TaskEventPayload withoutProcessOutput(TaskEventPayload source) {
        if (source == null) {
            return null;
        }
        TaskEventPayload payload = new TaskEventPayload();
        payload.setRawType(source.getRawType());
        payload.setItemType(source.getItemType());
        payload.setItemId(source.getItemId());
        payload.setStatus(source.getStatus());
        payload.setCommand(source.getCommand());
        payload.setToolName(source.getToolName());
        payload.setCallId(source.getCallId());
        payload.setArguments(hiddenText(source.getArguments(), HIDDEN_PROCESS_ARGUMENTS_TEXT));
        payload.setOutput(hiddenText(source.getOutput(), HIDDEN_PROCESS_OUTPUT_TEXT));
        payload.setMessage(source.getMessage());
        payload.setExitCode(source.getExitCode());
        payload.setActionKey(source.getActionKey());
        payload.setActionInstanceId(source.getActionInstanceId());
        payload.setActionLabel(source.getActionLabel());
        payload.setActionTarget(source.getActionTarget());
        payload.setInteractionId(source.getInteractionId());
        payload.setInteractionStatus(source.getInteractionStatus());
        payload.setInteractionInputType(source.getInteractionInputType());
        payload.setQuestion(source.getQuestion());
        payload.setInteractionContent(source.getInteractionContent());
        payload.setOptions(source.getOptions());
        payload.setActions(source.getActions());
        payload.setFields(source.getFields());
        payload.setRequired(source.getRequired());
        payload.setPlaceholder(source.getPlaceholder());
        payload.setAnswerHint(source.getAnswerHint());
        payload.setDefaultValue(source.getDefaultValue());
        payload.setContextKey(source.getContextKey());
        payload.setAnswerText(source.getAnswerText());
        payload.setSelectedValues(source.getSelectedValues());
        payload.setAnswerValues(source.getAnswerValues());
        payload.setInteractionAnswerAction(source.getInteractionAnswerAction());
        payload.setInteractionAnswerActionKey(source.getInteractionAnswerActionKey());
        payload.setTransientEvent(source.getTransientEvent());
        payload.setMetrics(source.getMetrics());
        payload.setActionGroupId(source.getActionGroupId());
        payload.setActionGroupSize(source.getActionGroupSize());
        payload.setSemantic(source.getSemantic());
        payload.setVisibility(source.getVisibility());
        payload.setModelTimingMode(source.getModelTimingMode());
        payload.setActionIcon(source.getActionIcon());
        payload.setDetailFile(false);
        payload.setCommandFile(false);
        payload.setArgumentsFile(false);
        payload.setOutputFile(false);
        payload.setMessageFile(false);
        payload.setActionTargetFile(false);
        return payload;
    }

    private String hiddenText(String value, String replacement) {
        return TextKit.blankToNull(value) == null ? null : replacement;
    }

    private TaskEventPayload normalizeResponsePayload(TaskEventEntity entity) {
        TaskEventPayload payload = entity.getPayload();
        if (payload == null || payload.getInteractionId() == null) {
            return payload;
        }
        if (TextKit.blankToNull(payload.getActionKey()) == null) {
            payload.setActionKey("interaction:user-input");
        }
        if (TextKit.blankToNull(payload.getActionInstanceId()) == null) {
            payload.setActionInstanceId("interaction:" + payload.getInteractionId());
        }
        if (TextKit.blankToNull(payload.getActionLabel()) == null) {
            payload.setActionLabel(entity.getTitle());
        }
        return payload;
    }

    private String responseDetail(TaskEventEntity entity, TaskEventPayload payload) {
        if (payload == null || payload.getInteractionId() == null) {
            return entity.getDetail();
        }
        String status = payload.getInteractionStatus();
        if ("PENDING".equals(status)) {
            return payload.getQuestion();
        }
        if ("ANSWERED".equals(status)) {
            String actionLabel = interactionActionLabel(payload);
            if (actionLabel != null) {
                return actionLabel;
            }
            if (payload.getSelectedValues() != null && !payload.getSelectedValues().isEmpty()) {
                return payload.getSelectedValues().stream()
                        .map(value -> interactionOptionLabel(payload.getOptions(), value))
                        .collect(Collectors.joining("、"));
            }
            return TextKit.blankToNull(payload.getAnswerText());
        }
        if ("SKIPPED".equals(status)) {
            return "已跳过";
        }
        if ("UNKNOWN".equals(status)) {
            return "不知道";
        }
        if ("CANCELED".equals(status)) {
            return "已取消";
        }
        if ("EXPIRED".equals(status)) {
            return "已超时";
        }
        return entity.getDetail();
    }

    private String interactionActionLabel(TaskEventPayload payload) {
        if (payload == null || payload.getActions() == null || TextKit.blankToNull(payload.getInteractionAnswerActionKey()) == null) {
            return null;
        }
        return payload.getActions().stream()
                .filter(action -> action != null && payload.getInteractionAnswerActionKey().equals(action.getKey()))
                .map(top.fusb.lingxi.dto.TaskInteractionAction::getLabel)
                .findFirst()
                .orElse(null);
    }

    private String interactionOptionLabel(List<TaskInteractionOption> options, String value) {
        if (options == null) {
            return value;
        }
        return options.stream()
                .filter(option -> option != null && value.equals(option.getValue()))
                .map(TaskInteractionOption::getLabel)
                .findFirst()
                .orElse(value);
    }

    private TaskEventPayload compactPayload(Long taskId, Long eventId, TaskEventPayload source) {
        if (source == null) {
            return null;
        }
        TaskEventPayload payload = new TaskEventPayload();
        payload.setRawType(TextKit.limit(source.getRawType(), MAX_EVENT_TEXT_LENGTH));
        payload.setItemType(TextKit.limit(source.getItemType(), MAX_EVENT_TEXT_LENGTH));
        payload.setItemId(TextKit.limit(source.getItemId(), MAX_EVENT_TEXT_LENGTH));
        payload.setStatus(TextKit.limit(source.getStatus(), MAX_EVENT_TEXT_LENGTH));
        payload.setCommand(compactText(taskId, eventId, "command", source.getCommand()));
        payload.setToolName(TextKit.limit(source.getToolName(), MAX_EVENT_TEXT_LENGTH));
        payload.setCallId(TextKit.limit(source.getCallId(), MAX_EVENT_TEXT_LENGTH));
        payload.setArguments(compactText(taskId, eventId, "arguments", source.getArguments()));
        payload.setOutput(compactText(taskId, eventId, "output", source.getOutput()));
        payload.setMessage(compactText(taskId, eventId, "message", source.getMessage()));
        payload.setExitCode(source.getExitCode());
        payload.setActionKey(TextKit.limit(source.getActionKey(), MAX_EVENT_TEXT_LENGTH));
        payload.setActionInstanceId(TextKit.limit(source.getActionInstanceId(), MAX_EVENT_TEXT_LENGTH));
        payload.setActionLabel(TextKit.limit(source.getActionLabel(), MAX_EVENT_TEXT_LENGTH));
        payload.setActionTarget(compactText(taskId, eventId, "actionTarget", source.getActionTarget()));
        payload.setTransientEvent(source.getTransientEvent());
        payload.setMetrics(source.getMetrics());
        payload.setActionGroupId(TextKit.limit(source.getActionGroupId(), MAX_EVENT_TEXT_LENGTH));
        payload.setActionGroupSize(source.getActionGroupSize());
        payload.setSemantic(source.getSemantic());
        payload.setVisibility(source.getVisibility());
        payload.setModelTimingMode(source.getModelTimingMode());
        payload.setActionIcon(source.getActionIcon());
        payload.setDetailFile(taskEventContentService.exists(taskId, eventId, "detail"));
        payload.setCommandFile(taskEventContentService.exists(taskId, eventId, "command"));
        payload.setArgumentsFile(taskEventContentService.exists(taskId, eventId, "arguments"));
        payload.setOutputFile(taskEventContentService.exists(taskId, eventId, "output"));
        payload.setMessageFile(taskEventContentService.exists(taskId, eventId, "message"));
        payload.setActionTargetFile(taskEventContentService.exists(taskId, eventId, "actionTarget"));
        payload.setInteractionId(source.getInteractionId());
        payload.setInteractionStatus(TextKit.limit(source.getInteractionStatus(), MAX_EVENT_TEXT_LENGTH));
        payload.setInteractionInputType(TextKit.limit(source.getInteractionInputType(), MAX_EVENT_TEXT_LENGTH));
        payload.setQuestion(compactText(taskId, eventId, "question", source.getQuestion()));
        payload.setInteractionContent(source.getInteractionContent());
        payload.setOptions(source.getOptions());
        payload.setActions(source.getActions());
        payload.setFields(source.getFields());
        payload.setRequired(source.getRequired());
        payload.setPlaceholder(TextKit.limit(source.getPlaceholder(), MAX_EVENT_TEXT_LENGTH));
        payload.setAnswerHint(TextKit.limit(source.getAnswerHint(), MAX_EVENT_TEXT_LENGTH));
        payload.setDefaultValue(TextKit.limit(source.getDefaultValue(), MAX_EVENT_TEXT_LENGTH));
        payload.setContextKey(TextKit.limit(source.getContextKey(), MAX_EVENT_TEXT_LENGTH));
        payload.setAnswerText(compactText(taskId, eventId, "answerText", source.getAnswerText()));
        payload.setSelectedValues(source.getSelectedValues());
        payload.setAnswerValues(source.getAnswerValues());
        payload.setInteractionAnswerAction(TextKit.limit(source.getInteractionAnswerAction(), MAX_EVENT_TEXT_LENGTH));
        payload.setInteractionAnswerActionKey(TextKit.limit(source.getInteractionAnswerActionKey(), MAX_EVENT_TEXT_LENGTH));
        return payload;
    }

    private String compactText(Long taskId, Long eventId, String field, String value) {
        return taskEventContentService.compact(taskId, eventId, field, value);
    }
}
