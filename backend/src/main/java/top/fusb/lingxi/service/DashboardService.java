package top.fusb.lingxi.service;

import top.fusb.lingxi.dto.DashboardTokenDimensionResponse;
import top.fusb.lingxi.dto.DashboardTokenTrendResponse;
import top.fusb.lingxi.dto.DashboardTokenUsageResponse;
import top.fusb.lingxi.dto.DashboardExecutionMetricsResponse;
import top.fusb.lingxi.dto.ResourceMemoryMetricsResponse;
import top.fusb.lingxi.dto.TaskExecutionMetricsResponse;
import top.fusb.lingxi.dto.TokenUsageSnapshot;
import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.enums.TaskStatus;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.kit.TextKit;
import top.fusb.lingxi.repository.AgentTaskRepository;
import top.fusb.lingxi.task.TaskMetricsService;
import top.fusb.lingxi.runtime.execution.TaskRuntimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.WeekFields;
import java.time.temporal.TemporalAdjusters;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final AgentTaskRepository agentTaskRepository;
    private final TaskRuntimeService taskRuntimeService;
    private final TaskMetricsService taskMetricsService;

    /**
     * 查询 token 使用仪表盘数据。
     *
     * @param createdStart 创建开始时间，ISO 本地时间字符串
     * @param createdEnd 创建结束时间，ISO 本地时间字符串
     * @param ownerId 提问人 ID，可为空
     * @param scenarioValue 提问类型，可为空
     * @param modelProfileIdValue 模型档案 ID，可为空
     * @param runtimeCodeValue Runtime 编码，可为空
     * @param granularityValue 趋势时间粒度
     * @return token 使用聚合结果
     * @throws BizException 时间格式不正确时抛出
     */
    @Transactional(readOnly = true)
    public DashboardTokenUsageResponse tokenUsage(String createdStart, String createdEnd, Long ownerId,
                                                   String scenarioValue, String modelProfileIdValue,
                                                   String runtimeCodeValue, String granularityValue) {
        String scenario = TextKit.blankToNull(scenarioValue);
        String modelProfileId = TextKit.blankToNull(modelProfileIdValue);
        String runtimeCode = TextKit.blankToNull(runtimeCodeValue);
        String granularity = normalizeGranularity(granularityValue);
        LocalDateTime startTime = parseDateTime(createdStart);
        LocalDateTime endTime = parseDateTime(createdEnd);
        List<AgentTaskEntity> tasks = agentTaskRepository.findAll(tokenUsageSpecification(
                startTime, endTime, ownerId, scenario, modelProfileId, runtimeCode));
        Map<Long, TokenUsageSnapshot> usageSnapshots = usageSnapshots(tasks);
        DashboardTokenUsageResponse response = new DashboardTokenUsageResponse();
        response.setSummary(summary(tasks, usageSnapshots));
        response.setResourceMemory(resourceMemoryMetrics(tasks));
        response.setExecutionExperience(executionMetrics(tasks));
        response.setByOwners(aggregateByOwner(tasks, usageSnapshots));
        response.setByQuestionTypes(aggregateByQuestionType(tasks, usageSnapshots));
        response.setByModels(aggregateByModel(tasks, usageSnapshots));
        response.setTrends(aggregateTrend(tasks, granularity, startTime, endTime, usageSnapshots));
        return response;
    }

    private ResourceMemoryMetricsResponse resourceMemoryMetrics(List<AgentTaskEntity> tasks) {
        ResourceMemoryMetricsResponse result = new ResourceMemoryMetricsResponse();
        for (AgentTaskEntity task : tasks) {
            ResourceMemoryMetricsResponse current = taskMetricsService.resourceMemoryMetrics(task);
            result.setSearchCount(result.getSearchCount() + current.getSearchCount());
            result.setHitCount(result.getHitCount() + current.getHitCount());
            result.setCandidateCount(result.getCandidateCount() + current.getCandidateCount());
            result.setSaveCount(result.getSaveCount() + current.getSaveCount());
            result.setCreatedCount(result.getCreatedCount() + current.getCreatedCount());
            result.setRefreshedCount(result.getRefreshedCount() + current.getRefreshedCount());
            result.setExpiredCount(result.getExpiredCount() + current.getExpiredCount());
            result.setInvalidatedCount(result.getInvalidatedCount() + current.getInvalidatedCount());
        }
        result.setEstimatedSavedDiscoveryCalls(result.getHitCount());
        return result;
    }

    private DashboardExecutionMetricsResponse executionMetrics(List<AgentTaskEntity> tasks) {
        DashboardExecutionMetricsResponse result = new DashboardExecutionMetricsResponse();
        Map<Long, TaskExecutionMetricsResponse> metricsByTask = taskMetricsService.executionMetrics(tasks);
        List<TaskExecutionMetricsResponse> metrics = tasks.stream()
                .map(task -> metricsByTask.get(task.getId()))
                .filter(java.util.Objects::nonNull)
                .toList();
        result.setMeasuredTaskCount((long) metrics.size());
        result.setAverageTotalDurationMs(averageMetric(metrics, TaskExecutionMetricsResponse::getTotalDurationMs));
        result.setAverageFirstFeedbackMs(averageMetric(metrics, TaskExecutionMetricsResponse::getFirstFeedbackMs));
        result.setAverageCommandDurationMs(averageMetric(metrics, TaskExecutionMetricsResponse::getCommandDurationMs));
        result.setAverageResultProcessingMs(averageMetric(metrics, TaskExecutionMetricsResponse::getResultProcessingMs));
        result.setCompactionCount(metrics.stream().mapToLong(value -> safe(value.getCompactionCount())).sum());
        result.setDuplicateCapabilityCallCount(metrics.stream()
                .mapToLong(value -> safe(value.getDuplicateCapabilityCallCount())).sum());
        return result;
    }

    private long averageMetric(List<TaskExecutionMetricsResponse> metrics,
                               java.util.function.Function<TaskExecutionMetricsResponse, Long> getter) {
        java.util.LongSummaryStatistics statistics = metrics.stream()
                .map(getter)
                .filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue)
                .summaryStatistics();
        return statistics.getCount() == 0 ? 0L : Math.round(statistics.getAverage());
    }

    private Specification<AgentTaskEntity> tokenUsageSpecification(LocalDateTime startTime,
                                                                   LocalDateTime endTime,
                                                                   Long ownerId,
                                                                   String scenario,
                                                                   String modelProfileId,
                                                                   String runtimeCode) {
        return (root, criteriaQuery, criteriaBuilder) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            if (ownerId != null) {
                predicates.add(criteriaBuilder.equal(root.get("owner").get("id"), ownerId));
            }
            if (scenario != null) {
                predicates.add(criteriaBuilder.equal(root.get("scenario"), scenario));
            }
            if (modelProfileId != null) {
                predicates.add(criteriaBuilder.equal(root.get("modelProfileId"), modelProfileId));
            }
            if (runtimeCode != null) {
                predicates.add(criteriaBuilder.equal(root.get("runtimeCode"), runtimeCode));
            }
            predicates.add(criteriaBuilder.isNotNull(root.get("scenarioCode")));
            if (startTime != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("createdAt"), startTime));
            }
            if (endTime != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("createdAt"), endTime));
            }
            return criteriaBuilder.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    private DashboardTokenDimensionResponse summary(List<AgentTaskEntity> tasks, Map<Long, TokenUsageSnapshot> usageSnapshots) {
        DashboardTokenDimensionResponse summary = new DashboardTokenDimensionResponse();
        summary.setDimensionKey("TOTAL");
        summary.setDimensionName("总计");
        tasks.forEach(task -> addTask(summary, task, usageSnapshots));
        applyAverage(summary);
        return summary;
    }

    private List<DashboardTokenDimensionResponse> aggregateByOwner(List<AgentTaskEntity> tasks, Map<Long, TokenUsageSnapshot> usageSnapshots) {
        Map<String, DashboardTokenDimensionResponse> rows = new LinkedHashMap<>();
        for (AgentTaskEntity task : tasks) {
            UserEntity owner = task.getOwner();
            String key = owner == null ? "UNKNOWN" : String.valueOf(owner.getId());
            DashboardTokenDimensionResponse row = rows.computeIfAbsent(key, ignored -> ownerRow(owner));
            addTask(row, task, usageSnapshots);
        }
        return sortedRows(rows);
    }

    private List<DashboardTokenDimensionResponse> aggregateByQuestionType(List<AgentTaskEntity> tasks, Map<Long, TokenUsageSnapshot> usageSnapshots) {
        Map<String, DashboardTokenDimensionResponse> rows = new LinkedHashMap<>();
        for (AgentTaskEntity task : tasks) {
            String key = questionTypeKey(task);
            DashboardTokenDimensionResponse row = rows.computeIfAbsent(key, ignored -> questionTypeRow(task));
            addTask(row, task, usageSnapshots);
        }
        return sortedRows(rows);
    }

    private List<DashboardTokenDimensionResponse> aggregateByModel(List<AgentTaskEntity> tasks,
                                                                    Map<Long, TokenUsageSnapshot> usageSnapshots) {
        Map<String, ModelUsageAggregate> rows = new LinkedHashMap<>();
        for (AgentTaskEntity task : tasks) {
            String identifier = modelIdentifier(task.getModelIdentifier(), task);
            rows.computeIfAbsent(identifier, ignored -> new ModelUsageAggregate(identifier, modelDisplayName(identifier, task)))
                    .addTask(task, usageOf(task, usageSnapshots));
        }
        return rows.values().stream()
                .map(ModelUsageAggregate::response)
                .sorted(Comparator.comparing(DashboardTokenDimensionResponse::getTotalTokens).reversed())
                .toList();
    }

    private List<DashboardTokenTrendResponse> aggregateTrend(List<AgentTaskEntity> tasks, String granularity,
                                                              LocalDateTime startTime, LocalDateTime endTime,
                                                              Map<Long, TokenUsageSnapshot> usageSnapshots) {
        Map<String, DashboardTokenTrendResponse> rows = new LinkedHashMap<>();
        if (!tasks.isEmpty()) {
            LocalDate firstDate = startTime == null
                    ? tasks.stream().map(AgentTaskEntity::getCreatedAt).min(LocalDateTime::compareTo).orElseThrow().toLocalDate()
                    : startTime.toLocalDate();
            LocalDate lastDate = endTime == null
                    ? tasks.stream().map(AgentTaskEntity::getCreatedAt).max(LocalDateTime::compareTo).orElseThrow().toLocalDate()
                    : endTime.toLocalDate();
            LocalDate period = switch (granularity) {
                case "month" -> firstDate.withDayOfMonth(1);
                case "week" -> firstDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                default -> firstDate;
            };
            while (!period.isAfter(lastDate)) {
                String date = trendKey(period, granularity);
                rows.put(date, trendRow(date));
                period = switch (granularity) {
                    case "month" -> period.plusMonths(1);
                    case "week" -> period.plusWeeks(1);
                    default -> period.plusDays(1);
                };
            }
        }
        tasks.stream()
                .sorted(Comparator.comparing(AgentTaskEntity::getCreatedAt))
                .forEach(task -> {
                    TokenUsageSnapshot usage = usageOf(task, usageSnapshots);
                    String date = trendKey(task.getCreatedAt().toLocalDate(), granularity);
                    DashboardTokenTrendResponse row = rows.computeIfAbsent(date, ignored -> trendRow(date));
                    row.setTaskCount(row.getTaskCount() + 1);
                    row.setRequestCount(row.getRequestCount() + safe(usage.getRequestCount()));
                    row.setInputTokens(row.getInputTokens() + safe(usage.getInputTokens()));
                    row.setCachedInputTokens(row.getCachedInputTokens() + safe(usage.getCachedInputTokens()));
                    row.setCacheCreationInputTokens(row.getCacheCreationInputTokens() + safe(usage.getCacheCreationInputTokens()));
                    row.setOutputTokens(row.getOutputTokens() + safe(usage.getOutputTokens()));
                    row.setReasoningOutputTokens(row.getReasoningOutputTokens() + safe(usage.getReasoningOutputTokens()));
                    row.setTotalTokens(row.getTotalTokens() + safe(usage.getTotalTokens()));
                });
        for (DashboardTokenTrendResponse row : rows.values()) {
            row.setAverageTokens(row.getTaskCount() == 0 ? 0 : row.getTotalTokens() / row.getTaskCount());
        }
        return List.copyOf(rows.values());
    }

    private String trendKey(LocalDate date, String granularity) {
        if ("month".equals(granularity)) {
            return date.withDayOfMonth(1).toString().substring(0, 7);
        }
        if ("week".equals(granularity)) {
            WeekFields weekFields = WeekFields.ISO;
            int weekYear = date.get(weekFields.weekBasedYear());
            int week = date.get(weekFields.weekOfWeekBasedYear());
            return weekYear + "-W" + String.format("%02d", week);
        }
        return date.toString();
    }

    private DashboardTokenDimensionResponse ownerRow(UserEntity owner) {
        DashboardTokenDimensionResponse row = new DashboardTokenDimensionResponse();
        if (owner == null) {
            row.setDimensionKey("UNKNOWN");
            row.setDimensionName("未知用户");
            return row;
        }
        row.setDimensionKey(String.valueOf(owner.getId()));
        row.setDimensionName(owner.getDisplayName());
        row.setOwnerUserId(owner.getId());
        row.setOwnerUsername(owner.getUsername());
        row.setOwnerDisplayName(owner.getDisplayName());
        return row;
    }

    private DashboardTokenDimensionResponse questionTypeRow(AgentTaskEntity task) {
        DashboardTokenDimensionResponse row = new DashboardTokenDimensionResponse();
        row.setScenario(task.getScenario());
        if (task.getScenarioCode() != null) {
            row.setDimensionKey("scenario:" + task.getScenarioCode());
            row.setDimensionName(task.getScenarioName());
            row.setScenarioCode(task.getScenarioCode());
            row.setScenarioName(task.getScenarioName());
            row.setScenarioColor(task.getScenarioColor());
            return row;
        }
        row.setDimensionKey(task.getScenario());
        row.setDimensionName(task.getScenario());
        return row;
    }

    private String questionTypeKey(AgentTaskEntity task) {
        if (task.getScenarioCode() != null) {
            return "scenario:" + task.getScenarioCode();
        }
        return task.getScenario();
    }

    private void addTask(DashboardTokenDimensionResponse row, AgentTaskEntity task, Map<Long, TokenUsageSnapshot> usageSnapshots) {
        TokenUsageSnapshot usage = usageOf(task, usageSnapshots);
        row.setTaskCount(row.getTaskCount() + 1);
        row.setRequestCount(row.getRequestCount() + safe(usage.getRequestCount()));
        addStatusCount(row, task.getStatus());
        row.setInputTokens(row.getInputTokens() + safe(usage.getInputTokens()));
        row.setCachedInputTokens(row.getCachedInputTokens() + safe(usage.getCachedInputTokens()));
        row.setCacheCreationInputTokens(row.getCacheCreationInputTokens() + safe(usage.getCacheCreationInputTokens()));
        row.setOutputTokens(row.getOutputTokens() + safe(usage.getOutputTokens()));
        row.setReasoningOutputTokens(row.getReasoningOutputTokens() + safe(usage.getReasoningOutputTokens()));
        row.setTotalTokens(row.getTotalTokens() + safe(usage.getTotalTokens()));
    }

    private void addStatusCount(DashboardTokenDimensionResponse row, TaskStatus status) {
        switch (status) {
            case PENDING -> row.setPendingCount(row.getPendingCount() + 1);
            case RUNNING -> row.setRunningCount(row.getRunningCount() + 1);
            case WAITING_USER -> row.setWaitingUserCount(row.getWaitingUserCount() + 1);
            case SUCCESS -> row.setSuccessCount(row.getSuccessCount() + 1);
            case FAILED -> row.setFailedCount(row.getFailedCount() + 1);
            case CANCELED -> row.setCanceledCount(row.getCanceledCount() + 1);
        }
    }

    private List<DashboardTokenDimensionResponse> sortedRows(Map<String, DashboardTokenDimensionResponse> rows) {
        return rows.values().stream()
                .peek(this::applyAverage)
                .sorted(Comparator.comparing(DashboardTokenDimensionResponse::getTotalTokens).reversed())
                .toList();
    }

    private DashboardTokenTrendResponse trendRow(String date) {
        DashboardTokenTrendResponse row = new DashboardTokenTrendResponse();
        row.setDate(date);
        return row;
    }

    private void applyAverage(DashboardTokenDimensionResponse row) {
        row.setAverageTokens(row.getTaskCount() == 0 ? 0 : row.getTotalTokens() / row.getTaskCount());
    }

    private String modelIdentifier(String value, AgentTaskEntity task) {
        String identifier = TextKit.blankToNull(value);
        if (identifier == null) {
            identifier = TextKit.blankToNull(task.getModelIdentifier());
        }
        return identifier == null ? "UNKNOWN" : identifier;
    }

    private String modelDisplayName(String identifier, AgentTaskEntity task) {
        if (identifier.equals(task.getModelIdentifier())) {
            String name = TextKit.blankToNull(task.getModelName());
            if (name != null) {
                return name;
            }
        }
        return "UNKNOWN".equals(identifier) ? "未记录模型" : identifier;
    }

    private long safe(Long value) {
        return value == null ? 0L : value;
    }

    private Map<Long, TokenUsageSnapshot> usageSnapshots(List<AgentTaskEntity> tasks) {
        Map<Long, TokenUsageSnapshot> values = new LinkedHashMap<>();
        for (AgentTaskEntity task : tasks) {
            values.put(task.getId(), taskRuntimeService.readUsage(task).orElseGet(() -> taskUsage(task)));
        }
        return values;
    }

    private TokenUsageSnapshot usageOf(AgentTaskEntity task, Map<Long, TokenUsageSnapshot> usageSnapshots) {
        return usageSnapshots.getOrDefault(task.getId(), taskUsage(task));
    }

    private TokenUsageSnapshot taskUsage(AgentTaskEntity task) {
        TokenUsageSnapshot usage = new TokenUsageSnapshot();
        usage.setRequestCount(safe(task.getRequestCount()));
        usage.setInputTokens(safe(task.getInputTokens()));
        usage.setCachedInputTokens(safe(task.getCachedInputTokens()));
        usage.setCacheCreationInputTokens(safe(task.getCacheCreationInputTokens()));
        usage.setOutputTokens(safe(task.getOutputTokens()));
        usage.setReasoningOutputTokens(safe(task.getReasoningOutputTokens()));
        usage.setTotalTokens(safe(task.getTotalTokens()));
        return usage;
    }

    private LocalDateTime parseDateTime(String value) {
        String text = TextKit.blankToNull(value);
        if (text == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "时间格式不正确");
        }
    }

    private String normalizeGranularity(String value) {
        String text = TextKit.blankToNull(value);
        if (text == null) {
            return "day";
        }
        if (List.of("day", "week", "month").contains(text)) {
            return text;
        }
        throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "时间粒度不支持");
    }

    private final class ModelUsageAggregate {

        private final DashboardTokenDimensionResponse response = new DashboardTokenDimensionResponse();
        private final Set<Long> taskIds = new HashSet<>();

        private ModelUsageAggregate(String identifier, String name) {
            response.setDimensionKey("model:" + identifier);
            response.setDimensionName(name);
            response.setModelName(name);
            response.setModelIdentifier(identifier);
        }

        private void addTask(AgentTaskEntity task, TokenUsageSnapshot usage) {
            addTaskOnce(task);
            response.setRequestCount(response.getRequestCount() + safe(usage.getRequestCount()));
            response.setInputTokens(response.getInputTokens() + safe(usage.getInputTokens()));
            response.setCachedInputTokens(response.getCachedInputTokens() + safe(usage.getCachedInputTokens()));
            response.setCacheCreationInputTokens(response.getCacheCreationInputTokens() + safe(usage.getCacheCreationInputTokens()));
            response.setOutputTokens(response.getOutputTokens() + safe(usage.getOutputTokens()));
            response.setReasoningOutputTokens(response.getReasoningOutputTokens() + safe(usage.getReasoningOutputTokens()));
            response.setTotalTokens(response.getTotalTokens() + safe(usage.getTotalTokens()));
        }

        private void addTaskOnce(AgentTaskEntity task) {
            if (!taskIds.add(task.getId())) {
                return;
            }
            response.setTaskCount(response.getTaskCount() + 1);
            addStatusCount(response, task.getStatus());
        }

        private DashboardTokenDimensionResponse response() {
            applyAverage(response);
            return response;
        }
    }
}
