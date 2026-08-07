package top.fusb.lingxi.task;

import top.fusb.lingxi.dto.TaskEventPayload;
import top.fusb.lingxi.dto.TaskExecutionMetricsResponse;
import top.fusb.lingxi.dto.TaskExecutionReportResponse;
import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.entity.TaskEventEntity;
import top.fusb.lingxi.enums.TaskEventStatus;
import top.fusb.lingxi.enums.TaskEventType;
import top.fusb.lingxi.enums.TaskExecutionReportStepType;
import top.fusb.lingxi.repository.TaskEventRepository;
import top.fusb.lingxi.runtime.api.model.RuntimeCostEstimate;
import top.fusb.lingxi.runtime.api.model.RuntimeCostRequest;
import top.fusb.lingxi.runtime.api.event.RuntimeEventSemantic;
import top.fusb.lingxi.runtime.api.event.RuntimeModelTimingMode;
import top.fusb.lingxi.runtime.core.AgentRuntimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TaskExecutionReportService {

    private final TaskEventRepository taskEventRepository;
    private final TaskMetricsService taskMetricsService;
    private final AgentRuntimeService agentRuntimeService;

    /**
     * 根据任务事件生成执行耗时报告。
     *
     * @param task 已完成访问权限校验的任务
     * @return 包含耗时构成、模型调用和时间线的执行报告
     */
    @Transactional(readOnly = true)
    public TaskExecutionReportResponse build(AgentTaskEntity task) {
        LocalDateTime reportStartedAt = task.getStartedAt() == null ? task.getCreatedAt() : task.getStartedAt();
        LocalDateTime reportEndedAt = task.getEndedAt() == null ? LocalDateTime.now() : task.getEndedAt();
        if (reportStartedAt == null || reportEndedAt.isBefore(reportStartedAt)) {
            reportStartedAt = reportEndedAt;
        }

        List<TaskEventEntity> events = taskEventRepository.findByTaskIdOrderByCreatedAtAsc(task.getId());
        List<CommandInterval> commands = pairCommands(events, reportEndedAt);
        List<ModelRequestInterval> modelRequests = pairModelRequests(events, reportEndedAt);
        List<Interval> mergedCommands = mergeIntervals(commands.stream()
                .map(command -> new Interval(command.startedAt(), command.endedAt()))
                .toList());
        List<Interval> mergedModelRequests = mergeIntervals(modelRequests.stream()
                .map(request -> new Interval(request.startedAt(), request.endedAt()))
                .toList());

        TaskExecutionReportResponse response = baseResponse(task, reportStartedAt, reportEndedAt);
        response.setCommandExecutionDurationMs(commands.stream().mapToLong(command -> durationMs(command.startedAt(), command.endedAt())).sum());
        response.setCommandWallDurationMs(overlapDuration(mergedCommands, reportStartedAt, reportEndedAt));
        applyStoredMetrics(response, taskMetricsService.resolvedExecutionMetrics(task));
        response.setSteps(buildSteps(events, commands, modelRequests, mergedCommands, mergedModelRequests,
                reportStartedAt, reportEndedAt, task.getEngineCompletedAt(), task.getEndedAt() != null));
        response.setModelCalls(buildModelCalls(events, modelRequests));
        applyTokenBreakdownSummary(response);
        applyModelCosts(task, response);

        long resultProcessingMs = clippedDuration(task.getEngineCompletedAt(), reportEndedAt, reportStartedAt, reportEndedAt);
        response.setResultProcessingMs(resultProcessingMs);
        response.setModelApiWaitDurationMs(stepDuration(response.getSteps(), TaskExecutionReportStepType.MODEL_API_WAIT));
        response.setModelStreamingDurationMs(stepDuration(response.getSteps(), TaskExecutionReportStepType.MODEL_STREAMING));
        response.setModelDecisionDurationMs(stepDuration(response.getSteps(), TaskExecutionReportStepType.MODEL_DECISION));
        response.setModelGenerationDurationMs(stepDuration(response.getSteps(), TaskExecutionReportStepType.MODEL_GENERATION));
        response.setModelProcessingDurationMs(stepDuration(response.getSteps(), TaskExecutionReportStepType.MODEL_PROCESSING));
        response.setOrchestrationDurationMs(stepDuration(response.getSteps(), TaskExecutionReportStepType.ORCHESTRATION));
        applyModelCallDiagnosis(response);
        return response;
    }

    private long stepDuration(List<TaskExecutionReportResponse.Step> steps, TaskExecutionReportStepType type) {
        return steps.stream().filter(step -> step.getType() == type)
                .mapToLong(TaskExecutionReportResponse.Step::getDurationMs).sum();
    }

    private TaskExecutionReportResponse baseResponse(AgentTaskEntity task, LocalDateTime startedAt, LocalDateTime endedAt) {
        TaskExecutionReportResponse response = new TaskExecutionReportResponse();
        response.setTaskId(task.getId());
        response.setTitle(task.getTitle());
        response.setStatus(task.getStatus());
        response.setRuntimeCode(task.getRuntimeCode());
        response.setModelProfileId(task.getModelProfileId());
        response.setModelName(task.getModelName());
        response.setModelIdentifier(task.getModelIdentifier());
        agentRuntimeService.descriptor(task.getRuntimeCode())
                .map(top.fusb.lingxi.runtime.api.model.RuntimeDescriptor::modelTimingNote)
                .ifPresent(response::setModelTimingNote);
        response.setStartedAt(startedAt);
        response.setEndedAt(endedAt);
        response.setTotalDurationMs(durationMs(startedAt, endedAt));
        response.setRequestCount(safe(task.getRequestCount()));
        response.setInputTokens(safe(task.getInputTokens()));
        response.setCachedInputTokens(safe(task.getCachedInputTokens()));
        response.setCacheCreationInputTokens(safe(task.getCacheCreationInputTokens()));
        response.setOutputTokens(safe(task.getOutputTokens()));
        response.setReasoningOutputTokens(safe(task.getReasoningOutputTokens()));
        response.setTotalTokens(safe(task.getTotalTokens()));
        return response;
    }

    private void applyStoredMetrics(TaskExecutionReportResponse response, TaskExecutionMetricsResponse metrics) {
        response.setFirstFeedbackMs(metrics.getFirstFeedbackMs());
        response.setCompactionCount(safe(metrics.getCompactionCount()));
        response.setDuplicateCapabilityCallCount(safe(metrics.getDuplicateCapabilityCallCount()));
    }

    private List<CommandInterval> pairCommands(List<TaskEventEntity> events, LocalDateTime reportEndedAt) {
        Map<String, Deque<TaskEventEntity>> running = new HashMap<>();
        List<CommandInterval> commands = new ArrayList<>();
        for (TaskEventEntity event : events) {
            if (TaskEventType.parse(event.getType()) != TaskEventType.COMMAND) {
                continue;
            }
            String key = commandInstanceKey(event);
            if (event.getStatus() == TaskEventStatus.RUNNING) {
                running.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addLast(event);
            } else if (event.getStatus() == TaskEventStatus.SUCCESS || event.getStatus() == TaskEventStatus.FAILED) {
                Deque<TaskEventEntity> starts = running.get(key);
                TaskEventEntity start = starts == null ? null : starts.pollFirst();
                if (start != null) {
                    commands.add(commandInterval(start, event.getCreatedAt(), event.getStatus()));
                }
            }
        }
        running.values().forEach(starts -> starts.forEach(start ->
                commands.add(commandInterval(start, reportEndedAt, TaskEventStatus.RUNNING))));
        commands.sort(Comparator.comparing(CommandInterval::startedAt));
        return commands;
    }

    private List<ModelRequestInterval> pairModelRequests(List<TaskEventEntity> events, LocalDateTime reportEndedAt) {
        Map<String, TaskEventEntity> starts = new HashMap<>();
        Map<String, LocalDateTime> firstResponses = new HashMap<>();
        List<ModelRequestInterval> requests = new ArrayList<>();
        for (TaskEventEntity event : events) {
            if (TaskEventType.parse(event.getType()) != TaskEventType.METRIC || event.getPayload() == null) {
                continue;
            }
            TaskEventPayload payload = event.getPayload();
            String requestId = firstPresent(payload.getActionInstanceId(), payload.getItemId());
            RuntimeEventSemantic semantic = payload.getSemantic();
            if (requestId == null || semantic == null) {
                continue;
            }
            if (semantic == RuntimeEventSemantic.MODEL_REQUEST_STARTED) {
                starts.put(requestId, event);
            } else if (semantic == RuntimeEventSemantic.MODEL_REQUEST_FIRST_RESPONSE) {
                firstResponses.putIfAbsent(requestId, metricEventTime(event));
            } else if (semantic == RuntimeEventSemantic.MODEL_REQUEST_COMPLETED
                    || semantic == RuntimeEventSemantic.MODEL_REQUEST_FAILED) {
                TaskEventEntity started = starts.remove(requestId);
                if (started != null) {
                    requests.add(new ModelRequestInterval(requestId + "-" + started.getId(), metricEventTime(started),
                            firstResponses.remove(requestId), metricEventTime(event), event.getStatus(),
                            payload.getModelTimingMode(), metrics(started), metrics(event)));
                }
            }
        }
        starts.forEach((requestId, started) -> requests.add(new ModelRequestInterval(
                requestId + "-" + started.getId(), metricEventTime(started), firstResponses.get(requestId), reportEndedAt,
                TaskEventStatus.RUNNING, started.getPayload().getModelTimingMode(), metrics(started), Map.of())));
        requests.sort(Comparator.comparing(ModelRequestInterval::startedAt));
        return requests;
    }

    private Map<String, String> metrics(TaskEventEntity event) {
        return event.getPayload() == null || event.getPayload().getMetrics() == null
                ? Map.of() : event.getPayload().getMetrics();
    }

    private LocalDateTime metricEventTime(TaskEventEntity event) {
        String timestamp = event.getPayload() == null || event.getPayload().getMetrics() == null
                ? null : event.getPayload().getMetrics().get("eventTimestamp");
        if (timestamp == null || timestamp.isBlank()) {
            return event.getCreatedAt();
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(timestamp), ZoneId.systemDefault());
        } catch (Exception ignored) {
            return event.getCreatedAt();
        }
    }

    private List<TaskExecutionReportResponse.ModelCall> buildModelCalls(List<TaskEventEntity> events,
                                                                         List<ModelRequestInterval> requests) {
        List<TaskExecutionReportResponse.ModelCall> calls = new ArrayList<>();
        int sequence = 1;
        for (ModelRequestInterval request : requests) {
            Map<String, String> values = new HashMap<>(request.startedMetrics());
            values.putAll(request.completedMetrics());
            EventMarker previous = previousMarker(events, request.startedAt());
            EventMarker next = nextMarker(events, request.endedAt());
            TaskExecutionReportResponse.ModelCall call = new TaskExecutionReportResponse.ModelCall();
            call.setId(request.id());
            call.setSequence(sequence++);
            call.setPurpose(waitTitle(previous, next));
            call.setModel(values.get("model"));
            call.setModelTimingMode(request.modelTimingMode());
            call.setResponseKind(values.get("responseKind"));
            call.setStatus(request.status());
            call.setStartedAt(request.startedAt());
            call.setFirstResponseAt(request.firstResponseAt());
            call.setEndedAt(request.endedAt());
            call.setFirstResponseMs(durationMs(request.startedAt(),
                    request.firstResponseAt() == null ? request.endedAt() : request.firstResponseAt()));
            call.setGenerationMs(request.firstResponseAt() == null ? 0L
                    : durationMs(request.firstResponseAt(), request.endedAt()));
            call.setTotalDurationMs(durationMs(request.startedAt(), request.endedAt()));
            call.setInputTokens(metricLong(values, "inputTokens"));
            call.setCachedInputTokens(metricLong(values, "cachedInputTokens"));
            call.setOutputTokens(metricLong(values, "outputTokens"));
            call.setReasoningOutputTokens(metricLong(values, "reasoningOutputTokens"));
            call.setTotalTokens(metricLong(values, "totalTokens"));
            call.setEstimatedInputTokens(metricLong(values, "estimatedInputTokens"));
            call.setSystemInstructionTokens(metricLong(values, "systemInstructionTokens"));
            call.setTaskInstructionTokens(metricLong(values, "taskInstructionTokens"));
            call.setMcpInstructionTokens(metricLong(values, "mcpInstructionTokens"));
            call.setToolSchemaTokens(metricLong(values, "toolSchemaTokens"));
            call.setConversationTokens(metricLong(values, "conversationTokens"));
            call.setToolResultTokens(metricLong(values, "toolResultTokens"));
            call.setImageTokens(metricLong(values, "imageTokens"));
            call.setMessageCount(metricInteger(values, "messageCount"));
            call.setToolDefinitionCount(metricInteger(values, "toolDefinitionCount"));
            call.setToolRequestCount(metricInteger(values, "toolRequestCount"));
            call.setErrorType(values.get("errorType"));
            call.setErrorMessage(values.get("errorMessage"));
            if (call.getModelTimingMode() != RuntimeModelTimingMode.OBSERVED && call.getGenerationMs() > 0
                    && call.getOutputTokens() != null && call.getOutputTokens() > 0) {
                call.setOutputTokensPerSecond(call.getOutputTokens() * 1000D / call.getGenerationMs());
            }
            calls.add(call);
        }
        return calls;
    }

    /**
     * 汇总逐次模型请求的本地 Token 分类估算和工具请求数。
     *
     * @param response 已完成逐次模型请求映射的执行报告
     * @return 无返回值
     */
    private void applyTokenBreakdownSummary(TaskExecutionReportResponse response) {
        List<TaskExecutionReportResponse.ModelCall> calls = response.getModelCalls();
        response.setModelRoundCount((long) calls.size());
        response.setToolCallCount(calls.stream().map(TaskExecutionReportResponse.ModelCall::getToolRequestCount)
                .filter(java.util.Objects::nonNull).mapToLong(Integer::longValue).sum());
        response.setEstimatedInputTokens(sumMetric(calls, TaskExecutionReportResponse.ModelCall::getEstimatedInputTokens));
        response.setSystemInstructionTokens(sumMetric(calls, TaskExecutionReportResponse.ModelCall::getSystemInstructionTokens));
        response.setTaskInstructionTokens(sumMetric(calls, TaskExecutionReportResponse.ModelCall::getTaskInstructionTokens));
        response.setMcpInstructionTokens(sumMetric(calls, TaskExecutionReportResponse.ModelCall::getMcpInstructionTokens));
        response.setToolSchemaTokens(sumMetric(calls, TaskExecutionReportResponse.ModelCall::getToolSchemaTokens));
        response.setConversationTokens(sumMetric(calls, TaskExecutionReportResponse.ModelCall::getConversationTokens));
        response.setToolResultTokens(sumMetric(calls, TaskExecutionReportResponse.ModelCall::getToolResultTokens));
        response.setImageTokens(sumMetric(calls, TaskExecutionReportResponse.ModelCall::getImageTokens));
    }

    /**
     * 对逐次模型调用中的可选长整型指标求和。
     *
     * @param calls 逐次模型调用列表
     * @param getter 目标指标读取函数
     * @return 忽略空值后的指标总和
     */
    private long sumMetric(List<TaskExecutionReportResponse.ModelCall> calls,
                           java.util.function.Function<TaskExecutionReportResponse.ModelCall, Long> getter) {
        return calls.stream().map(getter).filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum();
    }

    /**
     * 使用任务保存的价格快照逐次计算模型费用并汇总整次任务。
     *
     * @param task 当前任务，提供 Runtime 编码和模型价格快照
     * @param response 待补充费用的执行报告
     * @return 无返回值
     */
    private void applyModelCosts(AgentTaskEntity task, TaskExecutionReportResponse response) {
        for (TaskExecutionReportResponse.ModelCall call : response.getModelCalls()) {
            if (task.getModelPricing() == null || call.getStartedAt() == null) {
                continue;
            }
            String model = call.getModel() == null ? task.getModelIdentifier() : call.getModel();
            RuntimeCostRequest request = new RuntimeCostRequest(
                    model,
                    call.getStartedAt().atZone(ZoneId.systemDefault()).toInstant(),
                    call.getInputTokens(), call.getCachedInputTokens(), call.getOutputTokens(), task.getModelPricing());
            agentRuntimeService.estimateCost(task.getRuntimeCode(), request)
                    .ifPresent(cost -> applyCost(call, cost));
        }
        List<TaskExecutionReportResponse.ModelCall> pricedCalls = response.getModelCalls().stream()
                .filter(call -> call.getCostAmount() != null).toList();
        if (pricedCalls.isEmpty()) {
            return;
        }
        response.setCostCurrency(pricedCalls.get(0).getCostCurrency());
        response.setCostAmount(sumCost(pricedCalls, TaskExecutionReportResponse.ModelCall::getCostAmount));
        response.setCacheHitInputCost(sumCost(pricedCalls, TaskExecutionReportResponse.ModelCall::getCacheHitInputCost));
        response.setCacheMissInputCost(sumCost(pricedCalls, TaskExecutionReportResponse.ModelCall::getCacheMissInputCost));
        response.setOutputCost(sumCost(pricedCalls, TaskExecutionReportResponse.ModelCall::getOutputCost));
        response.setPriceTier(priceTier(pricedCalls));
    }

    private void applyCost(TaskExecutionReportResponse.ModelCall call, RuntimeCostEstimate cost) {
        call.setCostCurrency(cost.currency());
        call.setCostAmount(cost.amount());
        call.setCacheHitInputCost(cost.cacheHitInputAmount());
        call.setCacheMissInputCost(cost.cacheMissInputAmount());
        call.setOutputCost(cost.outputAmount());
        call.setPriceTier(cost.priceTier());
    }

    private BigDecimal sumCost(List<TaskExecutionReportResponse.ModelCall> calls,
                               java.util.function.Function<TaskExecutionReportResponse.ModelCall, BigDecimal> getter) {
        return calls.stream().map(getter).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String priceTier(List<TaskExecutionReportResponse.ModelCall> calls) {
        List<String> tiers = calls.stream().map(TaskExecutionReportResponse.ModelCall::getPriceTier)
                .filter(java.util.Objects::nonNull).distinct().toList();
        return tiers.size() == 1 ? tiers.get(0) : "MIXED";
    }

    private void applyModelCallDiagnosis(TaskExecutionReportResponse response) {
        List<TaskExecutionReportResponse.ModelCall> calls = response.getModelCalls();
        if (calls.isEmpty()) {
            TaskExecutionReportResponse.Step slowestOperationalStep = response.getSteps().stream()
                    .filter(step -> step.getType() == TaskExecutionReportStepType.COMMAND
                            || step.getType() == TaskExecutionReportStepType.ORCHESTRATION
                            || step.getType() == TaskExecutionReportStepType.RESULT_PROCESSING)
                    .max(Comparator.comparing(TaskExecutionReportResponse.Step::getDurationMs))
                    .orElse(null);
            if (slowestOperationalStep != null && slowestOperationalStep.getDurationMs() > 0) {
                response.setPrimaryFinding("最慢步骤：" + slowestOperationalStep.getTitle());
                response.setPrimaryFindingDetail(operationalFindingDetail(slowestOperationalStep)
                        + "该历史任务未采集逐次模型调用指标，不能比较具体模型调用。");
                return;
            }
            response.setPrimaryFinding("缺少逐次模型调用数据");
            response.setPrimaryFindingDetail("该任务只能按事件间隔推算，无法判断具体哪轮输入或生成偏慢。");
            return;
        }
        long medianInput = median(calls.stream().map(TaskExecutionReportResponse.ModelCall::getInputTokens)
                .filter(java.util.Objects::nonNull).filter(value -> value > 0).toList());
        long medianFirstResponse = median(calls.stream().map(TaskExecutionReportResponse.ModelCall::getFirstResponseMs).filter(value -> value > 0).toList());
        double medianSpeed = medianDouble(calls.stream().map(TaskExecutionReportResponse.ModelCall::getOutputTokensPerSecond)
                .filter(java.util.Objects::nonNull).filter(value -> value > 0).toList());
        for (TaskExecutionReportResponse.ModelCall call : calls) {
            boolean observedTiming = call.getModelTimingMode() == RuntimeModelTimingMode.OBSERVED;
            if (call.getErrorType() != null) {
                call.setDiagnosisType("ERROR");
                call.setDiagnosis("模型请求失败");
                call.setDiagnosisDetail(call.getErrorType() + (call.getErrorMessage() == null ? "" : "：" + call.getErrorMessage()));
                continue;
            }
            boolean usageAvailable = call.getInputTokens() != null || call.getOutputTokens() != null
                    || call.getMessageCount() != null || call.getToolDefinitionCount() != null;
            boolean largeInput = call.getInputTokens() != null && (call.getInputTokens() >= 64_000
                    || call.getInputTokens() >= 32_000
                    && (medianInput == 0 || call.getInputTokens() >= medianInput * 1.5D));
            boolean slowFirstResponse = call.getFirstResponseMs() >= 30_000 || call.getFirstResponseMs() >= 8_000
                    && (medianFirstResponse == 0 || call.getFirstResponseMs() >= medianFirstResponse * 1.8D);
            boolean slowGeneration = call.getOutputTokensPerSecond() != null && call.getGenerationMs() >= 3_000
                    && (call.getOutputTokensPerSecond() < 10D
                    || medianSpeed > 0 && call.getOutputTokensPerSecond() < medianSpeed * 0.55D);
            boolean reasoningHeavy = call.getReasoningOutputTokens() != null
                    && call.getReasoningOutputTokens() >= 1_000
                    && call.getReasoningOutputTokens() >= safe(call.getOutputTokens()) * 0.4D;
            if (largeInput && slowFirstResponse) {
                call.setDiagnosisType("LARGE_INPUT");
                call.setDiagnosis("输入上下文偏大");
                call.setDiagnosisDetail(observedTiming
                        ? "本轮输入明显高于任务内其他调用，首个可观测响应也同步变慢；观测事件无法继续拆分上游等待与模型内部推理。"
                        : "本轮输入明显高于任务内其他调用，首个响应也同步变慢，主要耗时可能在上下文预处理和首 Token 推理。");
            } else if (slowFirstResponse) {
                call.setDiagnosisType("SLOW_FIRST_RESPONSE");
                call.setDiagnosis(observedTiming ? "首个可观测响应偏慢" : "上游首个响应异常偏慢");
                call.setDiagnosisDetail(observedTiming
                        ? "该时段从请求开始到首个可观测响应项，包含上游等待和此前未单独上报的模型推理，不能解释为纯网络耗时。"
                        : call.getInputTokens() == null
                        ? "逐次输入指标未采集；当前只能确认首个响应异常偏慢，无法区分网络传输、上游排队或模型首 Token 推理。"
                        : "输入规模没有同步异常；可能来自网络传输、上游排队或模型首 Token 推理，当前接口无法继续三拆。");
            } else if (slowGeneration) {
                call.setDiagnosisType("SLOW_GENERATION");
                call.setDiagnosis("模型持续生成偏慢");
                call.setDiagnosisDetail("本轮输出速度明显低于任务内其他调用，慢点位于首个响应之后。");
            } else if (reasoningHeavy) {
                call.setDiagnosisType("HEAVY_REASONING");
                call.setDiagnosis("模型推理量较大");
                call.setDiagnosisDetail("本轮推理 Token 占比较高，耗时主要来自模型内部分析和内容生成。");
            } else if (largeInput) {
                call.setDiagnosisType("LARGE_INPUT");
                call.setDiagnosis("输入上下文偏大");
                call.setDiagnosisDetail("输入 Token 明显高于任务内其他调用，但当前首个响应耗时未显著异常。");
            } else {
                call.setDiagnosisType("NORMAL");
                call.setDiagnosis(usageAvailable ? "未见明显异常" : "仅采集到耗时");
                if (!usageAvailable) {
                    call.setDiagnosisDetail("该调用没有逐次输入、输出和请求上下文指标，只能比较请求耗时。");
                } else if (observedTiming) {
                    call.setDiagnosisDetail("相对本任务其他模型调用，输入规模和可观测响应耗时没有明显离群。");
                } else {
                    call.setDiagnosisDetail("相对本任务其他模型调用，输入规模、首个响应和生成速度没有明显离群。");
                }
            }
        }
        TaskExecutionReportResponse.ModelCall slowestModelCall = calls.stream()
                .max(Comparator.comparing(TaskExecutionReportResponse.ModelCall::getTotalDurationMs)).orElseThrow();
        TaskExecutionReportResponse.Step slowestOperationalStep = response.getSteps().stream()
                .filter(step -> step.getType() == TaskExecutionReportStepType.COMMAND
                        || step.getType() == TaskExecutionReportStepType.ORCHESTRATION
                        || step.getType() == TaskExecutionReportStepType.RESULT_PROCESSING)
                .max(Comparator.comparing(TaskExecutionReportResponse.Step::getDurationMs))
                .orElse(null);
        if (slowestOperationalStep != null
                && slowestOperationalStep.getDurationMs() > slowestModelCall.getTotalDurationMs()) {
            response.setPrimaryFinding("最慢步骤：" + slowestOperationalStep.getTitle());
            response.setPrimaryFindingDetail(operationalFindingDetail(slowestOperationalStep));
            return;
        }
        response.setPrimaryFinding("第 " + slowestModelCall.getSequence() + " 次模型调用最慢："
                + slowestModelCall.getPurpose());
        String usageDetail = slowestModelCall.getInputTokens() == null && slowestModelCall.getOutputTokens() == null
                ? "逐次 Token 指标未采集。"
                : "输入 " + safe(slowestModelCall.getInputTokens()) + " Token，输出 "
                + safe(slowestModelCall.getOutputTokens()) + " Token。";
        response.setPrimaryFindingDetail(slowestModelCall.getDiagnosis() + "，耗时 "
                + diagnosticDuration(slowestModelCall.getTotalDurationMs()) + "。" + usageDetail);
    }

    private String operationalFindingDetail(TaskExecutionReportResponse.Step step) {
        String duration = "耗时 " + diagnosticDuration(step.getDurationMs()) + "。";
        if (step.getType() == TaskExecutionReportStepType.COMMAND) {
            return duration + "该区间来自工具或其上游服务执行，不属于模型生成耗时。";
        }
        if (step.getType() == TaskExecutionReportStepType.RESULT_PROCESSING) {
            return duration + "该区间发生在模型执行完成之后，主要用于平台归档和结果组装。";
        }
        return duration + "该区间未被模型请求或工具执行覆盖，属于平台调度或尚未细分的运行开销。";
    }

    private String diagnosticDuration(long milliseconds) {
        if (milliseconds < 1_000) {
            return milliseconds + " 毫秒";
        }
        long seconds = Math.round(milliseconds / 1_000D);
        if (seconds < 60) {
            return seconds + " 秒";
        }
        return seconds / 60 + " 分 " + seconds % 60 + " 秒";
    }

    private Long metricLong(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer metricInteger(Map<String, String> values, String key) {
        Long value = metricLong(values, key);
        return value == null ? null : (int) Math.min(Integer.MAX_VALUE, value);
    }

    private long median(List<Long> values) {
        if (values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    private double medianDouble(List<Double> values) {
        if (values.isEmpty()) {
            return 0D;
        }
        List<Double> sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    private CommandInterval commandInterval(TaskEventEntity event, LocalDateTime endedAt, TaskEventStatus status) {
        TaskEventPayload payload = event.getPayload();
        String title = payload != null && payload.getActionLabel() != null && !payload.getActionLabel().isBlank()
                ? payload.getActionLabel() : event.getTitle();
        String detail = payload == null ? null : firstPresent(payload.getActionTarget(), payload.getCommand(), payload.getToolName());
        return new CommandInterval("command-" + (event.getId() == null ? commandInstanceKey(event) : event.getId()),
                title, detail, status, event.getCreatedAt(), endedAt);
    }

    private String commandInstanceKey(TaskEventEntity event) {
        TaskEventPayload payload = event.getPayload();
        if (payload != null) {
            String key = firstPresent(payload.getActionInstanceId(), payload.getCallId(), payload.getItemId());
            if (key != null) {
                return key;
            }
        }
        return event.getTitle() == null ? "command" : event.getTitle();
    }

    private List<TaskExecutionReportResponse.Step> buildSteps(List<TaskEventEntity> events, List<CommandInterval> commands,
                                                               List<ModelRequestInterval> modelRequests,
                                                               List<Interval> mergedCommands, List<Interval> mergedModelRequests,
                                                               LocalDateTime reportStartedAt, LocalDateTime reportEndedAt,
                                                               LocalDateTime engineCompletedAt, boolean completed) {
        List<TaskExecutionReportResponse.Step> steps = new ArrayList<>();
        for (CommandInterval command : commands) {
            TaskExecutionReportResponse.Step step = new TaskExecutionReportResponse.Step();
            step.setId(command.id());
            step.setType(TaskExecutionReportStepType.COMMAND);
            step.setTitle(command.title());
            step.setDetail(command.detail());
            step.setStatus(command.status());
            step.setStartedAt(command.startedAt());
            step.setEndedAt(command.endedAt());
            step.setDurationMs(durationMs(command.startedAt(), command.endedAt()));
            steps.add(step);
        }
        for (ModelRequestInterval request : modelRequests) {
            LocalDateTime firstResponseAt = request.firstResponseAt() == null ? request.endedAt() : request.firstResponseAt();
            steps.add(modelRequestStep(request.id() + "-first-response", TaskExecutionReportStepType.MODEL_API_WAIT,
                    "模型 API 首包等待", request.startedAt(), firstResponseAt, request.status()));
            if (request.firstResponseAt() != null && request.endedAt().isAfter(request.firstResponseAt())) {
                steps.add(modelRequestStep(request.id() + "-streaming", TaskExecutionReportStepType.MODEL_STREAMING,
                        "模型流式生成", request.firstResponseAt(), request.endedAt(), request.status()));
            }
        }

        List<Interval> occupied = new ArrayList<>(mergedCommands);
        occupied.addAll(mergedModelRequests);
        if (engineCompletedAt != null && engineCompletedAt.isBefore(reportEndedAt)) {
            occupied.add(new Interval(max(engineCompletedAt, reportStartedAt), reportEndedAt));
            TaskExecutionReportResponse.Step resultStep = new TaskExecutionReportResponse.Step();
            resultStep.setId("result-processing");
            resultStep.setType(TaskExecutionReportStepType.RESULT_PROCESSING);
            resultStep.setTitle("整理执行结果");
            resultStep.setStatus(completed ? TaskEventStatus.SUCCESS : TaskEventStatus.RUNNING);
            resultStep.setStartedAt(max(engineCompletedAt, reportStartedAt));
            resultStep.setEndedAt(reportEndedAt);
            resultStep.setDurationMs(durationMs(resultStep.getStartedAt(), resultStep.getEndedAt()));
            steps.add(resultStep);
        }

        List<Interval> mergedOccupied = mergeIntervals(occupied);
        LocalDateTime cursor = reportStartedAt;
        int waitIndex = 1;
        for (Interval interval : mergedOccupied) {
            LocalDateTime occupiedStart = max(interval.startedAt(), reportStartedAt);
            LocalDateTime occupiedEnd = min(interval.endedAt(), reportEndedAt);
            if (occupiedStart.isAfter(cursor)) {
                waitIndex = appendWaitSteps(steps, waitIndex, events, cursor, occupiedStart);
            }
            if (occupiedEnd.isAfter(cursor)) {
                cursor = occupiedEnd;
            }
        }
        if (reportEndedAt.isAfter(cursor)) {
            appendWaitSteps(steps, waitIndex, events, cursor, reportEndedAt);
        }
        steps.sort(Comparator.comparing(TaskExecutionReportResponse.Step::getStartedAt)
                .thenComparing(step -> step.getType().ordinal()));
        return steps;
    }

    private TaskExecutionReportResponse.Step modelRequestStep(String id, TaskExecutionReportStepType type, String title,
                                                               LocalDateTime startedAt, LocalDateTime endedAt,
                                                               TaskEventStatus status) {
        TaskExecutionReportResponse.Step step = new TaskExecutionReportResponse.Step();
        step.setId(id);
        step.setType(type);
        step.setTitle(title);
        step.setDetail(type == TaskExecutionReportStepType.MODEL_API_WAIT
                ? "包含网络传输、上游排队和首个响应生成" : "从首个响应到本轮响应完成");
        step.setStatus(status);
        step.setStartedAt(startedAt);
        step.setEndedAt(endedAt);
        step.setDurationMs(durationMs(startedAt, endedAt));
        return step;
    }

    private int appendWaitSteps(List<TaskExecutionReportResponse.Step> steps, int index,
                                List<TaskEventEntity> events,
                                LocalDateTime startedAt, LocalDateTime endedAt) {
        List<LocalDateTime> boundaries = events.stream()
                .filter(event -> TaskEventType.parse(event.getType()) == TaskEventType.AGENT_MESSAGE
                        || (TaskEventType.parse(event.getType()) == TaskEventType.SYSTEM && eventMarker(event) != null))
                .map(TaskEventEntity::getCreatedAt)
                .filter(boundary -> boundary.isAfter(startedAt) && boundary.isBefore(endedAt))
                .distinct()
                .sorted()
                .toList();
        LocalDateTime cursor = startedAt;
        for (LocalDateTime boundary : boundaries) {
            steps.add(waitStep(index++, events, cursor, boundary));
            cursor = boundary;
        }
        if (endedAt.isAfter(cursor)) {
            steps.add(waitStep(index++, events, cursor, endedAt));
        }
        return index;
    }

    private TaskExecutionReportResponse.Step waitStep(int index, List<TaskEventEntity> events,
                                                       LocalDateTime startedAt, LocalDateTime endedAt) {
        EventMarker previous = previousMarker(events, startedAt);
        EventMarker next = nextMarker(events, endedAt);
        TaskExecutionReportResponse.Step step = new TaskExecutionReportResponse.Step();
        step.setId("wait-" + index);
        step.setType(waitType(previous, next));
        step.setTitle(waitTitle(previous, next));
        step.setDetail(waitDetail(previous, next));
        step.setStatus(TaskEventStatus.INFO);
        step.setStartedAt(startedAt);
        step.setEndedAt(endedAt);
        step.setDurationMs(durationMs(startedAt, endedAt));
        return step;
    }

    private TaskExecutionReportStepType waitType(EventMarker previous, EventMarker next) {
        if (next != null && next.type() == TaskEventType.COMMAND) {
            return TaskExecutionReportStepType.MODEL_DECISION;
        }
        if (next != null && next.type() == TaskEventType.AGENT_MESSAGE) {
            return TaskExecutionReportStepType.MODEL_GENERATION;
        }
        if (next != null && next.type() == TaskEventType.SYSTEM) {
            return TaskExecutionReportStepType.ORCHESTRATION;
        }
        return TaskExecutionReportStepType.MODEL_PROCESSING;
    }

    private EventMarker previousMarker(List<TaskEventEntity> events, LocalDateTime at) {
        return events.stream()
                .filter(event -> !event.getCreatedAt().isAfter(at))
                .map(this::eventMarker)
                .filter(java.util.Objects::nonNull)
                .reduce((left, right) -> right)
                .orElse(null);
    }

    private EventMarker nextMarker(List<TaskEventEntity> events, LocalDateTime at) {
        return events.stream()
                .filter(event -> !event.getCreatedAt().isBefore(at))
                .map(this::eventMarker)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private EventMarker eventMarker(TaskEventEntity event) {
        TaskEventType type = TaskEventType.parse(event.getType());
        TaskEventPayload payload = event.getPayload();
        if (type == TaskEventType.COMMAND) {
            String label = payload != null && payload.getActionLabel() != null && !payload.getActionLabel().isBlank()
                    ? payload.getActionLabel() : event.getTitle();
            return new EventMarker(type, label, null, false);
        }
        if (type == TaskEventType.AGENT_MESSAGE) {
            boolean finalAnswer = payload != null && payload.getSemantic() == RuntimeEventSemantic.FINAL_ANSWER;
            Integer roundNo = parseRoundNo(payload == null ? null : payload.getItemId());
            String label = finalAnswer ? "最终回答" : roundNo == null ? "阶段反馈" : "第 " + roundNo + " 轮反馈";
            return new EventMarker(type, label, roundNo, finalAnswer);
        }
        if (type == TaskEventType.SYSTEM) {
            if ("任务模型开始处理".equals(event.getTitle())
                    || "任务模型处理完成".equals(event.getTitle())) {
                String label = payload != null && payload.getActionLabel() != null && !payload.getActionLabel().isBlank()
                        ? payload.getActionLabel() : event.getTitle();
                return new EventMarker(type, label, null, false);
            }
        }
        return null;
    }

    private Integer parseRoundNo(String itemId) {
        if (itemId == null || !itemId.startsWith("round-")) {
            return null;
        }
        try {
            return Integer.parseInt(itemId.substring("round-".length()));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String waitTitle(EventMarker previous, EventMarker next) {
        if (next != null && next.type() == TaskEventType.AGENT_MESSAGE) {
            String prefix = previous != null && previous.type() == TaskEventType.COMMAND
                    ? "处理“" + previous.label() + "”结果并" : "";
            return prefix + (next.finalAnswer() ? "生成最终回答"
                    : next.roundNo() == null ? "生成阶段反馈" : "生成第 " + next.roundNo() + " 轮反馈");
        }
        if (next != null && next.type() == TaskEventType.COMMAND) {
            if (previous != null && previous.type() == TaskEventType.COMMAND) {
                return "处理“" + previous.label() + "”结果并决定下一步";
            }
            if (previous != null && previous.type() == TaskEventType.AGENT_MESSAGE) {
                return "根据“" + previous.label() + "”继续分析";
            }
            return "分析问题并准备首次工具调用";
        }
        if (next != null && next.type() == TaskEventType.SYSTEM) {
            return "完成模型处理";
        }
        if (previous != null && previous.type() == TaskEventType.COMMAND) {
            return "处理“" + previous.label() + "”结果";
        }
        return "模型分析与决策";
    }

    private String waitDetail(EventMarker previous, EventMarker next) {
        List<String> parts = new ArrayList<>();
        if (previous != null) {
            parts.add("前一步：" + previous.label());
        }
        if (next != null) {
            parts.add("下一步：" + next.label());
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private List<Interval> mergeIntervals(List<Interval> intervals) {
        List<Interval> sorted = intervals.stream()
                .filter(interval -> interval.startedAt() != null && interval.endedAt() != null
                        && interval.endedAt().isAfter(interval.startedAt()))
                .sorted(Comparator.comparing(Interval::startedAt))
                .toList();
        List<Interval> merged = new ArrayList<>();
        for (Interval interval : sorted) {
            if (merged.isEmpty() || interval.startedAt().isAfter(merged.get(merged.size() - 1).endedAt())) {
                merged.add(interval);
                continue;
            }
            Interval previous = merged.remove(merged.size() - 1);
            merged.add(new Interval(previous.startedAt(), max(previous.endedAt(), interval.endedAt())));
        }
        return merged;
    }

    private long overlapDuration(List<Interval> intervals, LocalDateTime startedAt, LocalDateTime endedAt) {
        return intervals.stream().mapToLong(interval ->
                clippedDuration(interval.startedAt(), interval.endedAt(), startedAt, endedAt)).sum();
    }

    private long clippedDuration(LocalDateTime startedAt, LocalDateTime endedAt,
                                 LocalDateTime rangeStartedAt, LocalDateTime rangeEndedAt) {
        if (startedAt == null || endedAt == null || rangeStartedAt == null || rangeEndedAt == null) {
            return 0L;
        }
        LocalDateTime clippedStart = max(startedAt, rangeStartedAt);
        LocalDateTime clippedEnd = min(endedAt, rangeEndedAt);
        return durationMs(clippedStart, clippedEnd);
    }

    private long durationMs(LocalDateTime startedAt, LocalDateTime endedAt) {
        if (startedAt == null || endedAt == null || endedAt.isBefore(startedAt)) {
            return 0L;
        }
        return Duration.between(startedAt, endedAt).toMillis();
    }

    private long safe(Long value) {
        return value == null ? 0L : value;
    }

    private LocalDateTime min(LocalDateTime left, LocalDateTime right) {
        return left.isBefore(right) ? left : right;
    }

    private LocalDateTime max(LocalDateTime left, LocalDateTime right) {
        return left.isAfter(right) ? left : right;
    }

    private String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record Interval(LocalDateTime startedAt, LocalDateTime endedAt) {
    }

    private record CommandInterval(String id, String title, String detail, TaskEventStatus status,
                                   LocalDateTime startedAt, LocalDateTime endedAt) {
    }

    private record ModelRequestInterval(String id, LocalDateTime startedAt, LocalDateTime firstResponseAt,
                                        LocalDateTime endedAt, TaskEventStatus status,
                                        RuntimeModelTimingMode modelTimingMode,
                                        Map<String, String> startedMetrics, Map<String, String> completedMetrics) {
    }

    private record EventMarker(TaskEventType type, String label, Integer roundNo, boolean finalAnswer) {
    }
}
