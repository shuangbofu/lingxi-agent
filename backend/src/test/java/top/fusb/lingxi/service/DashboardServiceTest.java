package top.fusb.lingxi.service;

import top.fusb.lingxi.dto.DashboardTokenTrendResponse;
import top.fusb.lingxi.dto.DashboardTokenUsageResponse;
import top.fusb.lingxi.dto.ResourceMemoryMetricsResponse;
import top.fusb.lingxi.dto.TaskExecutionMetricsResponse;
import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.enums.TaskStatus;
import top.fusb.lingxi.repository.AgentTaskRepository;
import top.fusb.lingxi.task.TaskMetricsService;
import top.fusb.lingxi.runtime.execution.TaskRuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardServiceTest {

    @Test
    void fillsMissingDatesInsideSelectedTrendRange() {
        AgentTaskRepository repository = mock(AgentTaskRepository.class);
        TaskRuntimeService taskRuntimeService = mock(TaskRuntimeService.class);
        TaskMetricsService taskMetricsService = mock(TaskMetricsService.class);
        AgentTaskEntity first = task(1L, "2026-07-01T10:00:00", 100L);
        AgentTaskEntity last = task(2L, "2026-07-05T10:00:00", 200L);
        when(repository.findAll(org.mockito.ArgumentMatchers.<Specification<AgentTaskEntity>>any())).thenReturn(List.of(first, last));
        when(taskRuntimeService.readUsage(any(AgentTaskEntity.class))).thenReturn(Optional.empty());
        when(taskMetricsService.executionMetrics(any(List.class))).thenReturn(java.util.Map.of());
        when(taskMetricsService.resourceMemoryMetrics(any(AgentTaskEntity.class)))
                .thenReturn(new top.fusb.lingxi.dto.ResourceMemoryMetricsResponse());
        DashboardService service = new DashboardService(repository, taskRuntimeService, taskMetricsService);

        List<DashboardTokenTrendResponse> trends = service.tokenUsage(
                "2026-07-01T00:00:00", "2026-07-05T23:59:59", null, null, null, null, "day").getTrends();

        assertThat(trends).extracting(DashboardTokenTrendResponse::getDate)
                .containsExactly("2026-07-01", "2026-07-02", "2026-07-03", "2026-07-04", "2026-07-05");
        assertThat(trends).extracting(DashboardTokenTrendResponse::getTotalTokens)
                .containsExactly(100L, 0L, 0L, 0L, 200L);
        assertThat(trends).extracting(DashboardTokenTrendResponse::getTaskCount)
                .containsExactly(1L, 0L, 0L, 0L, 1L);
    }

    @Test
    void aggregatesResourceMemoryAndExecutionExperience() {
        AgentTaskRepository repository = mock(AgentTaskRepository.class);
        TaskRuntimeService taskRuntimeService = mock(TaskRuntimeService.class);
        TaskMetricsService taskMetricsService = mock(TaskMetricsService.class);
        AgentTaskEntity first = task(1L, "2026-07-01T10:00:00", 100L);
        AgentTaskEntity second = task(2L, "2026-07-02T10:00:00", 200L);
        when(repository.findAll(org.mockito.ArgumentMatchers.<Specification<AgentTaskEntity>>any()))
                .thenReturn(List.of(first, second));
        when(taskRuntimeService.readUsage(any(AgentTaskEntity.class))).thenReturn(Optional.empty());
        when(taskMetricsService.resourceMemoryMetrics(first)).thenReturn(memory(3L, 2L, 4L));
        when(taskMetricsService.resourceMemoryMetrics(second)).thenReturn(memory(2L, 1L, 3L));
        when(taskMetricsService.executionMetrics(any(List.class))).thenReturn(Map.of(
                1L, execution(1_000L, 4_000L, 2_000L, 1L),
                2L, execution(3_000L, 8_000L, 4_000L, 2L)));
        DashboardService service = new DashboardService(repository, taskRuntimeService, taskMetricsService);

        DashboardTokenUsageResponse response = service.tokenUsage(
                "2026-07-01T00:00:00", "2026-07-02T23:59:59", null, null, null, null, "day");

        assertThat(response.getResourceMemory().getSearchCount()).isEqualTo(5L);
        assertThat(response.getResourceMemory().getHitCount()).isEqualTo(3L);
        assertThat(response.getResourceMemory().getCandidateCount()).isEqualTo(7L);
        assertThat(response.getExecutionExperience().getMeasuredTaskCount()).isEqualTo(2L);
        assertThat(response.getExecutionExperience().getAverageFirstFeedbackMs()).isEqualTo(2_000L);
        assertThat(response.getExecutionExperience().getAverageCommandDurationMs()).isEqualTo(6_000L);
        assertThat(response.getExecutionExperience().getAverageResultProcessingMs()).isEqualTo(3_000L);
        assertThat(response.getExecutionExperience().getCompactionCount()).isEqualTo(3L);
        assertThat(response.getByModels()).extracting("modelIdentifier", "taskCount", "requestCount", "totalTokens")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("default-model", 2L, 2L, 300L));
    }

    private ResourceMemoryMetricsResponse memory(long searches, long hits, long candidates) {
        ResourceMemoryMetricsResponse metrics = new ResourceMemoryMetricsResponse();
        metrics.setSearchCount(searches);
        metrics.setHitCount(hits);
        metrics.setCandidateCount(candidates);
        return metrics;
    }

    private TaskExecutionMetricsResponse execution(long firstFeedback, long commandDuration,
                                                    long resultProcessing, long compactions) {
        TaskExecutionMetricsResponse metrics = new TaskExecutionMetricsResponse();
        metrics.setFirstFeedbackMs(firstFeedback);
        metrics.setCommandDurationMs(commandDuration);
        metrics.setResultProcessingMs(resultProcessing);
        metrics.setCompactionCount(compactions);
        return metrics;
    }

    private AgentTaskEntity task(Long id, String createdAt, Long totalTokens) {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(id);
        task.setScenario("test");
        task.setStatus(TaskStatus.SUCCESS);
        task.setModelName("测试模型");
        task.setModelIdentifier("default-model");
        task.setCreatedAt(LocalDateTime.parse(createdAt));
        task.setUpdatedAt(task.getCreatedAt());
        task.setRequestCount(1L);
        task.setTotalTokens(totalTokens);
        return task;
    }

}
