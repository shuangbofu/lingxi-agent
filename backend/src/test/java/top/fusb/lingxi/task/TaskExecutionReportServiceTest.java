package top.fusb.lingxi.task;

import top.fusb.lingxi.dto.TaskEventPayload;
import top.fusb.lingxi.dto.TaskExecutionMetricsResponse;
import top.fusb.lingxi.dto.TaskExecutionReportResponse;
import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.entity.TaskEventEntity;
import top.fusb.lingxi.enums.TaskEventStatus;
import top.fusb.lingxi.enums.TaskEventType;
import top.fusb.lingxi.enums.TaskExecutionReportStepType;
import top.fusb.lingxi.enums.TaskStatus;
import top.fusb.lingxi.repository.TaskEventRepository;
import top.fusb.lingxi.runtime.core.AgentRuntimeService;
import top.fusb.lingxi.runtime.api.model.RuntimeCostEstimate;
import top.fusb.lingxi.runtime.api.model.RuntimeCostRequest;
import top.fusb.lingxi.runtime.api.model.RuntimeModelPricing;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class TaskExecutionReportServiceTest {

    @Test
    void shouldNotDoubleCountParallelCommandsInWallDuration() {
        TaskEventRepository eventRepository = mock(TaskEventRepository.class);
        TaskMetricsService metricsService = mock(TaskMetricsService.class);
        TaskExecutionReportService service = new TaskExecutionReportService(eventRepository, metricsService, mock(AgentRuntimeService.class));
        LocalDateTime start = LocalDateTime.parse("2026-07-26T10:00:00");
        AgentTaskEntity task = task(121L, start, start.plusSeconds(10));
        List<TaskEventEntity> events = List.of(
                command(task, 1L, start.plusSeconds(2), "call-a", "读取日志", TaskEventStatus.RUNNING),
                command(task, 2L, start.plusSeconds(3), "call-b", "查询数据", TaskEventStatus.RUNNING),
                command(task, 3L, start.plusSeconds(5), "call-b", "查询数据", TaskEventStatus.SUCCESS),
                command(task, 4L, start.plusSeconds(6), "call-a", "读取日志", TaskEventStatus.SUCCESS)
        );
        when(eventRepository.findByTaskIdOrderByCreatedAtAsc(121L)).thenReturn(events);
        when(metricsService.resolvedExecutionMetrics(task)).thenReturn(new TaskExecutionMetricsResponse());

        TaskExecutionReportResponse report = service.build(task);

        assertThat(report.getCommandExecutionDurationMs()).isEqualTo(6_000L);
        assertThat(report.getCommandWallDurationMs()).isEqualTo(4_000L);
        assertThat(report.getModelDecisionDurationMs() + report.getModelProcessingDurationMs()).isEqualTo(6_000L);
        assertThat(report.getSteps()).filteredOn(step -> step.getType() == TaskExecutionReportStepType.COMMAND).hasSize(2);
        assertThat(report.getSteps()).filteredOn(step -> step.getType() == TaskExecutionReportStepType.MODEL_PROCESSING)
                .extracting(TaskExecutionReportResponse.Step::getTitle)
                .anyMatch(title -> title.contains("处理“读取日志”结果"));
    }

    @Test
    void shouldInferModelProcessingForHistoricalTask() {
        TaskEventRepository eventRepository = mock(TaskEventRepository.class);
        TaskMetricsService metricsService = mock(TaskMetricsService.class);
        TaskExecutionReportService service = new TaskExecutionReportService(eventRepository, metricsService, mock(AgentRuntimeService.class));
        LocalDateTime start = LocalDateTime.parse("2026-07-26T11:00:00");
        AgentTaskEntity task = task(122L, start, start.plusSeconds(8));
        when(eventRepository.findByTaskIdOrderByCreatedAtAsc(122L)).thenReturn(List.of());
        when(metricsService.resolvedExecutionMetrics(task)).thenReturn(new TaskExecutionMetricsResponse());

        TaskExecutionReportResponse report = service.build(task);

        assertThat(report.getModelProcessingDurationMs()).isEqualTo(8_000L);
    }

    @Test
    void shouldDescribeModelIntervalsWithPreviousActionAndRoundOutcome() {
        TaskEventRepository eventRepository = mock(TaskEventRepository.class);
        TaskMetricsService metricsService = mock(TaskMetricsService.class);
        TaskExecutionReportService service = new TaskExecutionReportService(eventRepository, metricsService, mock(AgentRuntimeService.class));
        LocalDateTime start = LocalDateTime.parse("2026-07-26T12:00:00");
        AgentTaskEntity task = task(123L, start, start.plusSeconds(30));
        List<TaskEventEntity> events = List.of(
                command(task, 1L, start.plusSeconds(2), "call-read", "读取文件", TaskEventStatus.RUNNING),
                command(task, 2L, start.plusSeconds(3), "call-read", "读取文件", TaskEventStatus.SUCCESS),
                agentMessage(task, 3L, start.plusSeconds(10), "langchain.progress", "round-2"),
                command(task, 4L, start.plusSeconds(11), "call-query", "数据查询", TaskEventStatus.RUNNING),
                command(task, 5L, start.plusSeconds(12), "call-query", "数据查询", TaskEventStatus.SUCCESS),
                agentMessage(task, 6L, start.plusSeconds(30), "langchain.answer", "round-4")
        );
        when(eventRepository.findByTaskIdOrderByCreatedAtAsc(123L)).thenReturn(events);
        when(metricsService.resolvedExecutionMetrics(task)).thenReturn(new TaskExecutionMetricsResponse());

        TaskExecutionReportResponse report = service.build(task);

        assertThat(report.getSteps()).filteredOn(step -> step.getType() == TaskExecutionReportStepType.MODEL_GENERATION)
                .extracting(TaskExecutionReportResponse.Step::getTitle)
                .contains("处理“读取文件”结果并生成第 2 轮反馈", "处理“数据查询”结果并生成最终回答");
    }

    @Test
    void shouldSeparateMeasuredFirstResponseAndStreamingDuration() {
        TaskEventRepository eventRepository = mock(TaskEventRepository.class);
        TaskMetricsService metricsService = mock(TaskMetricsService.class);
        TaskExecutionReportService service = new TaskExecutionReportService(eventRepository, metricsService, mock(AgentRuntimeService.class));
        LocalDateTime start = LocalDateTime.parse("2026-07-26T13:00:00");
        AgentTaskEntity task = task(124L, start, start.plusSeconds(10));
        List<TaskEventEntity> events = List.of(
                metric(task, 1L, start.plusSeconds(1), "request-1", "runtime.model.request.started", TaskEventStatus.RUNNING),
                metric(task, 2L, start.plusSeconds(4), "request-1", "runtime.model.request.first-response", TaskEventStatus.INFO),
                metric(task, 3L, start.plusSeconds(7), "request-1", "runtime.model.request.completed", TaskEventStatus.SUCCESS)
        );
        when(eventRepository.findByTaskIdOrderByCreatedAtAsc(124L)).thenReturn(events);
        when(metricsService.resolvedExecutionMetrics(task)).thenReturn(new TaskExecutionMetricsResponse());

        TaskExecutionReportResponse report = service.build(task);

        assertThat(report.getModelApiWaitDurationMs()).isEqualTo(3_000L);
        assertThat(report.getModelStreamingDurationMs()).isEqualTo(3_000L);
        assertThat(report.getModelCalls().get(0).getInputTokens()).isNull();
        assertThat(report.getModelCalls().get(0).getMessageCount()).isNull();
        assertThat(report.getModelCalls().get(0).getDiagnosis()).isEqualTo("仅采集到耗时");
        assertThat(report.getSteps()).extracting(TaskExecutionReportResponse.Step::getType)
                .contains(TaskExecutionReportStepType.MODEL_API_WAIT, TaskExecutionReportStepType.MODEL_STREAMING);
    }

    @Test
    void shouldUseRuntimeEventTimestampsForSessionModelMetrics() {
        TaskEventRepository eventRepository = mock(TaskEventRepository.class);
        TaskMetricsService metricsService = mock(TaskMetricsService.class);
        TaskExecutionReportService service = new TaskExecutionReportService(eventRepository, metricsService, mock(AgentRuntimeService.class));
        LocalDateTime start = LocalDateTime.parse("2026-07-26T13:10:00");
        AgentTaskEntity task = task(137L, start, start.plusSeconds(20));
        LocalDateTime persistedAt = start.plusSeconds(10);
        List<TaskEventEntity> events = List.of(
                metric(task, 1L, persistedAt, "request-1", "runtime.model.request.started", TaskEventStatus.RUNNING,
                        Map.of("eventTimestamp", timestamp(start.plusSeconds(1)))),
                metric(task, 2L, persistedAt, "request-1", "runtime.model.request.first-response", TaskEventStatus.INFO,
                        Map.of("eventTimestamp", timestamp(start.plusSeconds(4)))),
                metric(task, 3L, persistedAt, "request-1", "runtime.model.request.completed", TaskEventStatus.SUCCESS,
                        Map.of("eventTimestamp", timestamp(start.plusSeconds(7)), "modelTimingMode", "OBSERVED",
                                "inputTokens", "19378", "outputTokens", "185"))
        );
        when(eventRepository.findByTaskIdOrderByCreatedAtAsc(137L)).thenReturn(events);
        when(metricsService.resolvedExecutionMetrics(task)).thenReturn(new TaskExecutionMetricsResponse());

        TaskExecutionReportResponse report = service.build(task);

        TaskExecutionReportResponse.ModelCall call = report.getModelCalls().get(0);
        assertThat(call.getTotalDurationMs()).isEqualTo(6_000L);
        assertThat(call.getFirstResponseMs()).isEqualTo(3_000L);
        assertThat(call.getModelTimingMode()).isEqualTo(top.fusb.lingxi.runtime.api.event.RuntimeModelTimingMode.OBSERVED);
        assertThat(call.getInputTokens()).isEqualTo(19_378L);
        assertThat(call.getOutputTokensPerSecond()).isNull();
    }

    @Test
    void shouldUseEventIdentityWhenRuntimeRequestNumbersRestart() {
        TaskEventRepository eventRepository = mock(TaskEventRepository.class);
        TaskMetricsService metricsService = mock(TaskMetricsService.class);
        TaskExecutionReportService service = new TaskExecutionReportService(eventRepository, metricsService, mock(AgentRuntimeService.class));
        LocalDateTime start = LocalDateTime.parse("2026-07-26T13:30:00");
        AgentTaskEntity task = task(130L, start, start.plusSeconds(12));
        List<TaskEventEntity> events = List.of(
                metric(task, 11L, start.plusSeconds(1), "model-request-1", "runtime.model.request.started", TaskEventStatus.RUNNING),
                metric(task, 12L, start.plusSeconds(3), "model-request-1", "runtime.model.request.completed", TaskEventStatus.SUCCESS),
                metric(task, 21L, start.plusSeconds(5), "model-request-1", "runtime.model.request.started", TaskEventStatus.RUNNING),
                metric(task, 22L, start.plusSeconds(8), "model-request-1", "runtime.model.request.completed", TaskEventStatus.SUCCESS)
        );
        when(eventRepository.findByTaskIdOrderByCreatedAtAsc(130L)).thenReturn(events);
        when(metricsService.resolvedExecutionMetrics(task)).thenReturn(new TaskExecutionMetricsResponse());

        TaskExecutionReportResponse report = service.build(task);

        assertThat(report.getModelCalls()).extracting(TaskExecutionReportResponse.ModelCall::getId)
                .containsExactly("model-request-1-11", "model-request-1-21");
    }

    @Test
    void shouldIdentifyTheSpecificSlowModelCallAndItsInputEvidence() {
        TaskEventRepository eventRepository = mock(TaskEventRepository.class);
        TaskMetricsService metricsService = mock(TaskMetricsService.class);
        TaskExecutionReportService service = new TaskExecutionReportService(eventRepository, metricsService, mock(AgentRuntimeService.class));
        LocalDateTime start = LocalDateTime.parse("2026-07-26T14:00:00");
        AgentTaskEntity task = task(125L, start, start.plusSeconds(50));
        List<TaskEventEntity> events = List.of(
                metric(task, 1L, start.plusSeconds(1), "request-1", "runtime.model.request.started",
                        TaskEventStatus.RUNNING, Map.of("model", "gpt-test")),
                metric(task, 2L, start.plusSeconds(2), "request-1", "runtime.model.request.first-response",
                        TaskEventStatus.INFO, Map.of()),
                metric(task, 3L, start.plusSeconds(3), "request-1", "runtime.model.request.completed",
                        TaskEventStatus.SUCCESS, Map.of("inputTokens", "10000", "outputTokens", "100", "totalTokens", "10100")),
                command(task, 4L, start.plusSeconds(4), "call-query", "数据查询", TaskEventStatus.RUNNING),
                command(task, 5L, start.plusSeconds(5), "call-query", "数据查询", TaskEventStatus.SUCCESS),
                metric(task, 6L, start.plusSeconds(6), "request-2", "runtime.model.request.started",
                        TaskEventStatus.RUNNING, Map.of("model", "gpt-test", "messageCount", "18")),
                metric(task, 7L, start.plusSeconds(41), "request-2", "runtime.model.request.first-response",
                        TaskEventStatus.INFO, Map.of()),
                metric(task, 8L, start.plusSeconds(46), "request-2", "runtime.model.request.completed",
                        TaskEventStatus.SUCCESS, Map.of("inputTokens", "80000", "cachedInputTokens", "40000",
                                "outputTokens", "500", "reasoningOutputTokens", "200", "totalTokens", "80500")),
                agentMessage(task, 9L, start.plusSeconds(47), "langchain.answer", "round-3")
        );
        when(eventRepository.findByTaskIdOrderByCreatedAtAsc(125L)).thenReturn(events);
        when(metricsService.resolvedExecutionMetrics(task)).thenReturn(new TaskExecutionMetricsResponse());

        TaskExecutionReportResponse report = service.build(task);

        assertThat(report.getModelCalls()).hasSize(2);
        TaskExecutionReportResponse.ModelCall secondCall = report.getModelCalls().get(1);
        assertThat(secondCall.getPurpose()).isEqualTo("处理“数据查询”结果并生成最终回答");
        assertThat(secondCall.getInputTokens()).isEqualTo(80_000L);
        assertThat(secondCall.getCachedInputTokens()).isEqualTo(40_000L);
        assertThat(secondCall.getOutputTokensPerSecond()).isEqualTo(100D);
        assertThat(secondCall.getDiagnosisType()).isEqualTo("LARGE_INPUT");
        assertThat(report.getPrimaryFinding()).startsWith("第 2 次模型调用最慢");
        assertThat(report.getPrimaryFindingDetail()).contains("输入 80000 Token");
    }

    @Test
    void shouldPreferAConcreteSlowToolOverAFasterModelCall() {
        TaskEventRepository eventRepository = mock(TaskEventRepository.class);
        TaskMetricsService metricsService = mock(TaskMetricsService.class);
        TaskExecutionReportService service = new TaskExecutionReportService(eventRepository, metricsService, mock(AgentRuntimeService.class));
        LocalDateTime start = LocalDateTime.parse("2026-07-26T15:00:00");
        AgentTaskEntity task = task(126L, start, start.plusSeconds(20));
        List<TaskEventEntity> events = List.of(
                metric(task, 1L, start.plusSeconds(1), "request-1", "runtime.model.request.started",
                        TaskEventStatus.RUNNING, Map.of()),
                metric(task, 2L, start.plusSeconds(2), "request-1", "runtime.model.request.completed",
                        TaskEventStatus.SUCCESS, Map.of("inputTokens", "1000", "outputTokens", "20")),
                command(task, 3L, start.plusSeconds(3), "call-log", "读取日志", TaskEventStatus.RUNNING),
                command(task, 4L, start.plusSeconds(15), "call-log", "读取日志", TaskEventStatus.SUCCESS)
        );
        when(eventRepository.findByTaskIdOrderByCreatedAtAsc(126L)).thenReturn(events);
        when(metricsService.resolvedExecutionMetrics(task)).thenReturn(new TaskExecutionMetricsResponse());

        TaskExecutionReportResponse report = service.build(task);

        assertThat(report.getPrimaryFinding()).isEqualTo("最慢步骤：读取日志");
        assertThat(report.getPrimaryFindingDetail()).contains("不属于模型生成耗时");
    }

    @Test
    void shouldAggregateRuntimeCostFromMeasuredModelCalls() {
        TaskEventRepository eventRepository = mock(TaskEventRepository.class);
        TaskMetricsService metricsService = mock(TaskMetricsService.class);
        AgentRuntimeService runtimeService = mock(AgentRuntimeService.class);
        TaskExecutionReportService service = new TaskExecutionReportService(
                eventRepository, metricsService, runtimeService);
        LocalDateTime start = LocalDateTime.parse("2026-07-26T16:00:00");
        AgentTaskEntity task = task(140L, start, start.plusSeconds(5));
        task.setRuntimeCode("langchain");
        task.setModelIdentifier("deepseek-v4");
        task.setModelPricing(new RuntimeModelPricing(
                "CNY", "Asia/Shanghai", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, List.of()));
        List<TaskEventEntity> events = List.of(
                metric(task, 1L, start.plusSeconds(1), "request-1", "runtime.model.request.started",
                        TaskEventStatus.RUNNING, Map.of("model", "deepseek-v4")),
                metric(task, 2L, start.plusSeconds(3), "request-1", "runtime.model.request.completed",
                        TaskEventStatus.SUCCESS, Map.of("inputTokens", "1000", "cachedInputTokens", "400",
                                "outputTokens", "100", "totalTokens", "1100"))
        );
        RuntimeCostEstimate estimate = new RuntimeCostEstimate(
                "CNY", "STANDARD", new BigDecimal("0.0013"),
                new BigDecimal("0.0004"), new BigDecimal("0.0006"), new BigDecimal("0.0003"));
        when(eventRepository.findByTaskIdOrderByCreatedAtAsc(140L)).thenReturn(events);
        when(metricsService.resolvedExecutionMetrics(task)).thenReturn(new TaskExecutionMetricsResponse());
        when(runtimeService.estimateCost(eq("langchain"), any(RuntimeCostRequest.class)))
                .thenReturn(java.util.Optional.of(estimate));

        TaskExecutionReportResponse report = service.build(task);

        assertThat(report.getCostAmount()).isEqualByComparingTo("0.0013");
        assertThat(report.getCacheHitInputCost()).isEqualByComparingTo("0.0004");
        assertThat(report.getModelCalls().get(0).getCostAmount()).isEqualByComparingTo("0.0013");
        assertThat(report.getPriceTier()).isEqualTo("STANDARD");
    }

    @Test
    void shouldAggregateRequestTokenBreakdownAndToolRounds() {
        TaskEventRepository eventRepository = mock(TaskEventRepository.class);
        TaskMetricsService metricsService = mock(TaskMetricsService.class);
        TaskExecutionReportService service = new TaskExecutionReportService(
                eventRepository, metricsService, mock(AgentRuntimeService.class));
        LocalDateTime start = LocalDateTime.parse("2026-07-26T17:00:00");
        AgentTaskEntity task = task(141L, start, start.plusSeconds(8));
        List<TaskEventEntity> events = List.of(
                metric(task, 1L, start.plusSeconds(1), "request-1", "runtime.model.request.started",
                        TaskEventStatus.RUNNING, Map.ofEntries(
                                Map.entry("estimatedInputTokens", "1000"),
                                Map.entry("systemInstructionTokens", "100"),
                                Map.entry("taskInstructionTokens", "150"),
                                Map.entry("mcpInstructionTokens", "50"),
                                Map.entry("toolSchemaTokens", "200"),
                                Map.entry("conversationTokens", "300"),
                                Map.entry("toolResultTokens", "180"),
                                Map.entry("imageTokens", "20"))),
                metric(task, 2L, start.plusSeconds(3), "request-1", "runtime.model.request.completed",
                        TaskEventStatus.SUCCESS, Map.of("inputTokens", "900", "cachedInputTokens", "450",
                                "outputTokens", "100", "toolRequestCount", "2")),
                metric(task, 3L, start.plusSeconds(4), "compaction-request-1", "runtime.model.request.started",
                        TaskEventStatus.RUNNING, Map.of("estimatedInputTokens", "600",
                                "systemInstructionTokens", "80",
                                "conversationTokens", "520")),
                metric(task, 4L, start.plusSeconds(7), "compaction-request-1", "runtime.model.request.completed",
                        TaskEventStatus.SUCCESS, Map.of("inputTokens", "580", "outputTokens", "80",
                                "toolRequestCount", "0"))
        );
        when(eventRepository.findByTaskIdOrderByCreatedAtAsc(141L)).thenReturn(events);
        when(metricsService.resolvedExecutionMetrics(task)).thenReturn(new TaskExecutionMetricsResponse());

        TaskExecutionReportResponse report = service.build(task);

        assertThat(report.getModelRoundCount()).isEqualTo(2L);
        assertThat(report.getToolCallCount()).isEqualTo(2L);
        assertThat(report.getEstimatedInputTokens()).isEqualTo(1_600L);
        assertThat(report.getConversationTokens()).isEqualTo(820L);
        assertThat(report.getModelCalls().get(0).getImageTokens()).isEqualTo(20L);
    }

    private AgentTaskEntity task(Long id, LocalDateTime startedAt, LocalDateTime endedAt) {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(id);
        task.setTitle("分析最近日志");
        task.setStatus(TaskStatus.SUCCESS);
        task.setRuntimeCode("codex-runtime");
        task.setModelIdentifier("gpt-test");
        task.setCreatedAt(startedAt);
        task.setStartedAt(startedAt);
        task.setEndedAt(endedAt);
        return task;
    }

    private TaskEventEntity command(AgentTaskEntity task, Long id, LocalDateTime createdAt, String instanceId,
                                    String label, TaskEventStatus status) {
        TaskEventPayload payload = new TaskEventPayload();
        payload.setActionInstanceId(instanceId);
        payload.setActionLabel(label);
        TaskEventEntity event = new TaskEventEntity();
        event.setId(id);
        event.setTask(task);
        event.setType(TaskEventType.COMMAND.name());
        event.setStatus(status);
        event.setTitle(label);
        event.setPayload(payload);
        event.setCreatedAt(createdAt);
        return event;
    }

    private TaskEventEntity agentMessage(AgentTaskEntity task, Long id, LocalDateTime createdAt,
                                         String rawType, String itemId) {
        TaskEventPayload payload = new TaskEventPayload();
        payload.setRawType(rawType);
        payload.setItemId(itemId);
        if ("langchain.answer".equals(rawType)) {
            payload.setSemantic(top.fusb.lingxi.runtime.api.event.RuntimeEventSemantic.FINAL_ANSWER);
        }
        TaskEventEntity event = new TaskEventEntity();
        event.setId(id);
        event.setTask(task);
        event.setType(TaskEventType.AGENT_MESSAGE.name());
        event.setStatus(TaskEventStatus.SUCCESS);
        event.setTitle(rawType);
        event.setPayload(payload);
        event.setCreatedAt(createdAt);
        return event;
    }

    private TaskEventEntity metric(AgentTaskEntity task, Long id, LocalDateTime createdAt, String requestId,
                                   String rawType, TaskEventStatus status) {
        return metric(task, id, createdAt, requestId, rawType, status, Map.of());
    }

    private TaskEventEntity metric(AgentTaskEntity task, Long id, LocalDateTime createdAt, String requestId,
                                   String rawType, TaskEventStatus status, Map<String, String> metrics) {
        TaskEventPayload payload = new TaskEventPayload();
        payload.setRawType(rawType);
        payload.setItemId(requestId);
        payload.setActionInstanceId(requestId);
        payload.setMetrics(metrics);
        payload.setSemantic(switch (rawType) {
            case "runtime.model.request.started" -> top.fusb.lingxi.runtime.api.event.RuntimeEventSemantic.MODEL_REQUEST_STARTED;
            case "runtime.model.request.first-response" -> top.fusb.lingxi.runtime.api.event.RuntimeEventSemantic.MODEL_REQUEST_FIRST_RESPONSE;
            case "runtime.model.request.completed" -> top.fusb.lingxi.runtime.api.event.RuntimeEventSemantic.MODEL_REQUEST_COMPLETED;
            case "runtime.model.request.failed" -> top.fusb.lingxi.runtime.api.event.RuntimeEventSemantic.MODEL_REQUEST_FAILED;
            default -> null;
        });
        if ("OBSERVED".equals(metrics.get("modelTimingMode"))) {
            payload.setModelTimingMode(top.fusb.lingxi.runtime.api.event.RuntimeModelTimingMode.OBSERVED);
        }
        TaskEventEntity event = new TaskEventEntity();
        event.setId(id);
        event.setTask(task);
        event.setType(TaskEventType.METRIC.name());
        event.setStatus(status);
        event.setTitle(rawType);
        event.setPayload(payload);
        event.setCreatedAt(createdAt);
        return event;
    }

    private String timestamp(LocalDateTime value) {
        return value.atZone(ZoneId.systemDefault()).toInstant().toString();
    }
}
