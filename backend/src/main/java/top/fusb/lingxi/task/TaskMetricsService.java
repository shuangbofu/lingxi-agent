package top.fusb.lingxi.task;

import top.fusb.lingxi.dto.ResourceMemoryMetricsResponse;
import top.fusb.lingxi.dto.TaskEventPayload;
import top.fusb.lingxi.dto.TaskExecutionMetricsResponse;
import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.entity.TaskEventEntity;
import top.fusb.lingxi.enums.TaskEventStatus;
import top.fusb.lingxi.enums.TaskEventType;
import top.fusb.lingxi.runtime.api.event.RuntimeEventSemantic;
import top.fusb.lingxi.repository.TaskEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TaskMetricsService {

    private final TaskEventRepository taskEventRepository;

    /**
     * 读取单个任务的执行体验指标。
     *
     * @param task 当前任务
     * @return 根据任务时间和事件协议计算的执行指标
     */
    public TaskExecutionMetricsResponse executionMetrics(AgentTaskEntity task) {
        if (task == null || task.getId() == null) {
            return new TaskExecutionMetricsResponse();
        }
        return executionMetrics(List.of(task)).getOrDefault(task.getId(), new TaskExecutionMetricsResponse());
    }

    /**
     * 读取任务已固化的执行指标，不访问任务事件表。
     *
     * @param task 当前任务
     * @return 可供详情和轮次摘要直接展示的执行指标
     */
    public TaskExecutionMetricsResponse storedExecutionMetrics(AgentTaskEntity task) {
        TaskExecutionMetricsResponse response = new TaskExecutionMetricsResponse();
        if (task == null) {
            return response;
        }
        LocalDateTime start = task.getStartedAt() == null ? task.getCreatedAt() : task.getStartedAt();
        response.setTotalDurationMs(durationMs(start, task.getEndedAt()));
        response.setFirstFeedbackMs(task.getExecutionFirstFeedbackMs());
        response.setCommandDurationMs(safe(task.getExecutionCommandDurationMs()));
        response.setResultProcessingMs(durationMs(task.getEngineCompletedAt(), task.getEndedAt()));
        response.setCompactionCount(safe(task.getExecutionCompactionCount()));
        response.setDuplicateCapabilityCallCount(safe(task.getExecutionDuplicateCapabilityCallCount()));
        return response;
    }

    /**
     * 读取任务指标；历史终态任务未固化指标时，根据事件重新计算。
     *
     * @param task 当前任务
     * @return 已固化指标，或根据历史事件补算的指标
     */
    public TaskExecutionMetricsResponse resolvedExecutionMetrics(AgentTaskEntity task) {
        if (task == null || task.getId() == null || task.getEndedAt() == null || hasStoredExecutionMetrics(task)) {
            return storedExecutionMetrics(task);
        }
        return executionMetrics(task);
    }

    /**
     * 从当前任务事件计算最终指标并写回任务实体，供后续摘要接口直接读取。
     *
     * @param task 已设置结束时间的任务实体
     * @return 本次固化的执行指标
     */
    public TaskExecutionMetricsResponse captureExecutionMetrics(AgentTaskEntity task) {
        TaskExecutionMetricsResponse response = executionMetrics(task);
        task.setExecutionFirstFeedbackMs(response.getFirstFeedbackMs());
        task.setExecutionCommandDurationMs(response.getCommandDurationMs());
        task.setExecutionCompactionCount(response.getCompactionCount());
        task.setExecutionDuplicateCapabilityCallCount(response.getDuplicateCapabilityCallCount());
        return response;
    }

    /**
     * 批量读取任务事件并计算执行体验指标，供仪表盘避免逐任务查询。
     *
     * @param tasks 待统计任务
     * @return 以任务 ID 为键的执行指标
     */
    public Map<Long, TaskExecutionMetricsResponse> executionMetrics(List<AgentTaskEntity> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return Map.of();
        }
        Map<Long, AgentTaskEntity> taskById = tasks.stream()
                .filter(task -> task.getId() != null)
                .collect(Collectors.toMap(AgentTaskEntity::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Map<Long, List<TaskEventEntity>> eventsByTask = new LinkedHashMap<>();
        for (TaskEventEntity event : taskEventRepository.findByTask_IdInOrderByTask_IdAscCreatedAtAsc(taskById.keySet())) {
            eventsByTask.computeIfAbsent(event.getTask().getId(), ignored -> new ArrayList<>()).add(event);
        }
        Map<Long, TaskExecutionMetricsResponse> result = new LinkedHashMap<>();
        taskById.forEach((taskId, task) -> result.put(taskId,
                calculateExecutionMetrics(task, eventsByTask.getOrDefault(taskId, List.of()))));
        return result;
    }

    /**
     * 将任务上的资源记忆累计字段转换为接口指标。
     *
     * @param task 当前任务
     * @return 资源记忆检索、命中、写入和失效指标
     */
    public ResourceMemoryMetricsResponse resourceMemoryMetrics(AgentTaskEntity task) {
        ResourceMemoryMetricsResponse response = new ResourceMemoryMetricsResponse();
        if (task == null) {
            return response;
        }
        response.setSearchCount(safe(task.getResourceMemorySearchCount()));
        response.setHitCount(safe(task.getResourceMemoryHitCount()));
        response.setCandidateCount(safe(task.getResourceMemoryCandidateCount()));
        response.setSaveCount(safe(task.getResourceMemorySaveCount()));
        response.setCreatedCount(safe(task.getResourceMemoryCreatedCount()));
        response.setRefreshedCount(safe(task.getResourceMemoryRefreshedCount()));
        response.setExpiredCount(safe(task.getResourceMemoryExpiredCount()));
        response.setInvalidatedCount(safe(task.getResourceMemoryInvalidatedCount()));
        response.setEstimatedSavedDiscoveryCalls(response.getHitCount());
        return response;
    }

    private TaskExecutionMetricsResponse calculateExecutionMetrics(AgentTaskEntity task, List<TaskEventEntity> events) {
        TaskExecutionMetricsResponse response = new TaskExecutionMetricsResponse();
        LocalDateTime start = task.getStartedAt() == null ? task.getCreatedAt() : task.getStartedAt();
        LocalDateTime end = task.getEndedAt();
        response.setTotalDurationMs(durationMs(start, end));

        LocalDateTime firstFeedbackAt = null;
        long commandDurationMs = 0L;
        long compactionCount = 0L;
        long duplicateCapabilityCallCount = 0L;
        Map<String, LocalDateTime> commandStarts = new HashMap<>();
        Map<String, Integer> capabilityCalls = new HashMap<>();
        for (TaskEventEntity event : events) {
            TaskEventType type = TaskEventType.parse(event.getType());
            TaskEventPayload payload = event.getPayload();
            if (firstFeedbackAt == null && type != TaskEventType.SYSTEM && type != TaskEventType.METRIC) {
                firstFeedbackAt = event.getCreatedAt();
            }
            if (payload != null && payload.getSemantic() == RuntimeEventSemantic.CONTEXT_COMPACTION) {
                compactionCount++;
            }
            if (type != TaskEventType.COMMAND) {
                continue;
            }
            String instanceKey = commandInstanceKey(event, payload);
            if (event.getStatus() == TaskEventStatus.RUNNING) {
                commandStarts.putIfAbsent(instanceKey, event.getCreatedAt());
                continue;
            }
            if (event.getStatus() != TaskEventStatus.SUCCESS && event.getStatus() != TaskEventStatus.FAILED) {
                continue;
            }
            LocalDateTime commandStart = commandStarts.remove(instanceKey);
            Long elapsed = durationMs(commandStart, event.getCreatedAt());
            commandDurationMs += elapsed == null ? 0L : elapsed;
            String actionKey = payload == null ? null : payload.getActionKey();
            if (actionKey != null && actionKey.startsWith("capability:")) {
                String signature = actionKey + "\n" + normalizeCommandTarget(payload);
                int count = capabilityCalls.merge(signature, 1, Integer::sum);
                if (count > 1) {
                    duplicateCapabilityCallCount++;
                }
            }
        }
        if (end != null) {
            for (LocalDateTime commandStart : commandStarts.values()) {
                Long elapsed = durationMs(commandStart, end);
                commandDurationMs += elapsed == null ? 0L : elapsed;
            }
        }
        response.setFirstFeedbackMs(durationMs(start, firstFeedbackAt));
        response.setCommandDurationMs(commandDurationMs);
        response.setResultProcessingMs(durationMs(task.getEngineCompletedAt(), end));
        response.setCompactionCount(compactionCount);
        response.setDuplicateCapabilityCallCount(duplicateCapabilityCallCount);
        return response;
    }

    private boolean hasStoredExecutionMetrics(AgentTaskEntity task) {
        return task.getExecutionCommandDurationMs() != null
                && task.getExecutionCompactionCount() != null
                && task.getExecutionDuplicateCapabilityCallCount() != null;
    }

    private String commandInstanceKey(TaskEventEntity event, TaskEventPayload payload) {
        if (payload != null) {
            for (String value : new String[]{payload.getActionInstanceId(), payload.getCallId(), payload.getItemId()}) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return event.getTitle() == null ? "command" : event.getTitle();
    }

    private String normalizeCommandTarget(TaskEventPayload payload) {
        if (payload == null) {
            return "";
        }
        String value = payload.getActionTarget() == null ? payload.getCommand() : payload.getActionTarget();
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private Long durationMs(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || end.isBefore(start)) {
            return null;
        }
        return Duration.between(start, end).toMillis();
    }

    private long safe(Long value) {
        return value == null ? 0L : value;
    }
}
