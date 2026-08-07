package top.fusb.lingxi.task;

import top.fusb.lingxi.config.LingxiProperties;
import top.fusb.lingxi.auth.UserUsageQuotaService;
import top.fusb.lingxi.dto.TaskEventResponse;
import top.fusb.lingxi.dto.TaskExecutionEvent;
import top.fusb.lingxi.dto.TaskEventPayload;
import top.fusb.lingxi.dto.TaskResultData;
import top.fusb.lingxi.dto.TokenUsageSnapshot;
import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.enums.TaskEventStatus;
import top.fusb.lingxi.enums.TaskEventType;
import top.fusb.lingxi.enums.TaskStatus;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.kit.TextKit;
import top.fusb.lingxi.repository.AgentTaskRepository;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionResult;
import top.fusb.lingxi.runtime.config.RuntimeConfigChangedEvent;
import top.fusb.lingxi.runtime.config.RuntimeConfigService;
import top.fusb.lingxi.runtime.config.RuntimeConcurrencyPolicy;
import top.fusb.lingxi.runtime.execution.TaskRuntimeService;
import top.fusb.lingxi.service.ModuleDefinitionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskRunner {

    private static final long QUEUE_NOTICE_INTERVAL_MILLIS = 20_000L;
    private static final int USER_TASK_PRIORITY = 0;
    private static final String USAGE_LIMIT_MESSAGE = "Token 用量已达到当前配额上限";

    private final AgentTaskRepository agentTaskRepository;
    private final TaskRuntimeService taskRuntimeService;
    private final TaskEventService taskEventService;
    private final TaskEventStreamService taskEventStreamService;
    private final TaskAgentWorkspaceService taskAgentWorkspaceService;
    private final TaskResultStructuringService taskResultStructuringService;
    private final TaskMetricsService taskMetricsService;
    private final TaskContentService taskContentService;
    private final TaskArtifactService taskArtifactService;
    private final TaskStoragePathService taskStoragePathService;
    private final ModuleDefinitionService moduleDefinitionService;
    private final LingxiProperties lingxiProperties;
    private final RuntimeConfigService runtimeConfigService;
    private final TransactionTemplate transactionTemplate;
    private final UserUsageQuotaService userUsageQuotaService;
    private final Deque<Long> pendingTasks = new ArrayDeque<>();
    private final Set<Long> runningTasks = new HashSet<>();
    private final ConcurrentHashMap<Long, Long> lastQueueNoticeAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> taskQueuePriorities = new ConcurrentHashMap<>();
    private final Map<Long, String> taskModelProfileIds = new HashMap<>();
    private final ExecutorService taskExecutor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService queueMaintenanceExecutor = Executors.newSingleThreadScheduledExecutor();

    @PostConstruct
    public void startQueueNoticeLoop() {
        recoverUnfinishedTasks();
        queueMaintenanceExecutor.scheduleWithFixedDelay(this::maintainQueueSafely,
                QUEUE_NOTICE_INTERVAL_MILLIS, QUEUE_NOTICE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * 提交任务到固定后台队列执行。
     *
     * @param taskId 任务 ID
     * @return 无返回值
     */
    public void submit(Long taskId) {
        TaskQueueMetadata metadata = taskQueueMetadata(taskId);
        synchronized (this) {
            enqueueTask(taskId, metadata.priority(), metadata.modelProfileId());
            log.info("提交任务到后台队列 taskId={} priority={} pendingCount={} runningCount={} maxConcurrency={}",
                    taskId, metadata.priority(), pendingTasks.size(), runningTasks.size(), maxConcurrency());
        }
        schedule();
    }

    /**
     * 取消还未开始或正在执行的任务。
     *
     * @param taskId 任务 ID
     * @return 无返回值
     */
    public void cancel(Long taskId) {
        boolean running;
        synchronized (this) {
            boolean removed = pendingTasks.remove(taskId);
            lastQueueNoticeAt.remove(taskId);
            taskQueuePriorities.remove(taskId);
            if (removed) {
                taskModelProfileIds.remove(taskId);
                log.info("取消排队任务 taskId={}", taskId);
                return;
            }
            running = runningTasks.contains(taskId);
            log.info("取消任务 taskId={} running={}", taskId, running);
        }
        if (running) {
            taskRuntimeService.cancel(taskId);
        }
    }

    /**
     * 等待指定任务的旧 Runtime 执行完全退出，避免恢复后被旧执行结果覆盖。
     *
     * @param taskId 任务 ID
     * @param timeoutMillis 最大等待毫秒数
     * @return 任务已经停止时返回 true，等待超时时返回 false
     * @throws InterruptedException 当前线程等待期间被中断时抛出
     */
    public boolean awaitStopped(Long taskId, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(timeoutMillis, 0L);
        synchronized (this) {
            while (runningTasks.contains(taskId)) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    return false;
                }
                wait(remaining);
            }
            return true;
        }
    }

    /**
     * 清理当前任务执行工作区，使同一轮修改输入后不会读取已取消执行留下的中间文件。
     *
     * @param taskId 当前任务 ID
     * @return 无返回值
     * @throws BizException 工作区文件无法清理时抛出
     */
    public void clearWorkspace(Long taskId) {
        Path workspaceRoot = taskStoragePathService.workspaceRoot(taskId);
        if (!Files.isDirectory(workspaceRoot)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(workspaceRoot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (Exception exception) {
            throw new BizException(ErrorCode.PROCESS_ERROR, ErrorSubCode.DATA_LOAD_FAILED,
                    "清理任务工作区失败：" + exception.getMessage());
        }
    }

    private void schedule() {
        List<Long> startingTasks;
        synchronized (this) {
            startingTasks = drainStartableTasks();
        }
        startingTasks.forEach(taskId -> CompletableFuture.runAsync(() -> run(taskId), taskExecutor));
    }

    @EventListener
    public void onRuntimeConfigChanged(RuntimeConfigChangedEvent event) {
        log.info("运行配置变更，重新调度任务队列 maxConcurrency={}", maxConcurrency());
        enqueuePendingTasks();
    }

    private void run(Long taskId) {
        log.info("后台线程开始处理任务 taskId={}", taskId);
        AgentTaskEntity task = null;
        String mainWorkspacePath = null;
        try {
            boolean recovering = requireEntity(taskId).getStartedAt() != null;
            if (!markRunning(taskId)) {
                log.info("任务已取消，跳过执行 taskId={}", taskId);
                return;
            }
            task = requireEntity(taskId);
            log.info("task started taskId={} scenario={} recovering={}", taskId, task.getScenario(), recovering);
            mainWorkspacePath = prepareTaskWorkspace(task, recovering);
            long timeout = taskRuntimeService.timeoutSeconds(task);
            Set<String> capabilityCodes = agentTaskRepository.findTaskCapabilityCodesByTaskId(taskId);
            Map<String, Set<String>> capabilityCommands = task.getEnabledCapabilityCommands() != null
                    ? task.getEnabledCapabilityCommands()
                    : Map.of();
            RuntimeExecutionResult result = taskRuntimeService.execute(
                    task,
                    mainWorkspacePath,
                    capabilityCodes,
                    capabilityCommands,
                    recovering,
                    timeout,
                    event -> appendEvent(taskId, event),
                    delta -> taskEventStreamService.publishMessageDelta(taskId, delta),
                    usage -> updateTokenUsage(taskId, usage)
            );
            finish(taskId, mainWorkspacePath, result);
        } catch (Exception | LinkageError e) {
            log.error("任务执行失败 taskId={} message={}", taskId, e.getMessage(), e);
            fail(taskId, e.getMessage());
        } finally {
            synchronized (this) {
                runningTasks.remove(taskId);
                lastQueueNoticeAt.remove(taskId);
                taskQueuePriorities.remove(taskId);
                taskModelProfileIds.remove(taskId);
                notifyAll();
            }
            schedule();
        }
    }

    private String prepareTaskWorkspace(AgentTaskEntity task, boolean recovering) {
        Path root = taskStoragePathService.workspaceRoot(task.getId());
        try {
            Files.createDirectories(root);
            taskAgentWorkspaceService.prepare(task, root, recovering);
            return root.toString();
        } catch (Exception e) {
            throw new BizException(ErrorCode.PROCESS_ERROR, ErrorSubCode.RUNTIME_EXEC_FAILED,
                    "Failed to create task workspace: " + e.getMessage());
        }
    }

    private boolean markRunning(Long taskId) {
        AgentTaskEntity pendingTask = requireEntity(taskId);
        if (pendingTask.getStatus() == TaskStatus.CANCELED) {
            return false;
        }
        userUsageQuotaService.requireAvailable(pendingTask.getOwner());
        Boolean marked = transactionTemplate.execute(status -> {
            AgentTaskEntity entity = requireEntity(taskId);
            if (entity.getStatus() == TaskStatus.CANCELED) {
                return false;
            }
            entity.setStatus(TaskStatus.RUNNING);
            LocalDateTime now = LocalDateTime.now();
            if (entity.getStartedAt() == null) {
                entity.setStartedAt(now);
            }
            entity.setUpdatedAt(now);
            AgentTaskEntity saved = agentTaskRepository.save(entity);
            touchConversationRoot(saved, saved.getUpdatedAt());
            return true;
        });
        return Boolean.TRUE.equals(marked);
    }

    public void appendEvent(Long taskId, TaskExecutionEvent event) {
        if (isTransientEvent(event)) {
            taskEventStreamService.publishTransient(taskId, event);
            return;
        }
        TaskEventResponse savedEvent = taskEventService.save(taskId, event);
        if (event != null && event.getType() == TaskEventType.METRIC) {
            return;
        }
        if (event != null
                && (event.getType() == TaskEventType.AGENT_MESSAGE || event.getType() == TaskEventType.THINKING)
                && event.getPayload() != null && event.getPayload().getItemId() != null) {
            taskEventStreamService.completeMessage(taskId, event.getPayload().getItemId());
        }
        appendOutput(taskId, event.displayText());
        try {
            taskEventStreamService.publish(taskId, savedEvent);
        } catch (Exception e) {
            log.info("任务事件实时推送失败，已保留事件记录 taskId={} eventId={} message={}", taskId, savedEvent.getId(), e.getMessage());
        }
    }

    /**
     * 判断事件是否只用于当前 SSE 展示，不需要落库和追加 stdout。
     *
     * @param event 任务执行事件
     * @return 是否为临时展示事件
     */
    private boolean isTransientEvent(TaskExecutionEvent event) {
        return event != null && event.getPayload() != null && Boolean.TRUE.equals(event.getPayload().getTransientEvent());
    }

    public void appendOutput(Long taskId, String line) {
        transactionTemplate.executeWithoutResult(status -> {
            String stdoutText = taskContentService.append(taskId, "stdout", line);
            LocalDateTime updatedAt = LocalDateTime.now();
            int updated = agentTaskRepository.updateOutputSummary(taskId, stdoutText, updatedAt);
            if (updated == 0) {
                throw new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.TASK_NOT_FOUND);
            }
            Long rootId = agentTaskRepository.findConversationRootTaskId(taskId);
            if (rootId != null && !rootId.equals(taskId)) {
                agentTaskRepository.touchConversationRootUpdatedAt(rootId, updatedAt);
            }
        });
    }

    public void updateTokenUsage(Long taskId, TokenUsageSnapshot usage) {
        if (usage == null) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            LocalDateTime updatedAt = LocalDateTime.now();
            int updated = agentTaskRepository.updateRuntimeUsage(
                    taskId,
                    usage.getRequestCount(),
                    usage.getInputTokens(),
                    usage.getCachedInputTokens(),
                    usage.getCacheCreationInputTokens(),
                    usage.getOutputTokens(),
                    usage.getReasoningOutputTokens(),
                    usage.getTotalTokens(),
                    updatedAt);
            if (updated == 0) {
                throw new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.TASK_NOT_FOUND);
            }
            Long rootId = agentTaskRepository.findConversationRootTaskId(taskId);
            if (rootId != null && !rootId.equals(taskId)) {
                agentTaskRepository.touchConversationRootUpdatedAt(rootId, updatedAt);
            }
        });
        taskEventStreamService.publishUsage(taskId, usage);
        AgentTaskEntity task = requireEntity(taskId);
        if (userUsageQuotaService.isExceeded(task)) {
            stopForUsageLimit(taskId);
        }
    }

    public void finish(Long taskId, String workspacePath, RuntimeExecutionResult result) {
        AgentTaskEntity saved = transactionTemplate.execute(status -> {
            AgentTaskEntity task = requireEntity(taskId);
            if (isTerminal(task.getStatus())) {
                return null;
            }
            task.setStatus(result.exitCode() == 0 ? TaskStatus.SUCCESS : TaskStatus.FAILED);
            task.setExitCode(result.exitCode());
            task.setStdoutText(taskContentService.writeIfAbsent(taskId, "stdout", result.stdoutText()));
            task.setStderrText(taskContentService.write(taskId, "stderr", result.stderrText()));
            if (result.exitCode() == 0) {
                String resultText = taskArtifactService.archiveLinkedFiles(taskId, workspacePath, result.resultText());
                TaskResultData resultData = taskResultStructuringService.structure(resultFormat(task), resultRenderer(task), resultText, recommendedScenarioCodes(task));
                task.setResultText(taskContentService.write(taskId, "result", resultData.getMarkdown()));
                task.setResultData(resultData);
            } else {
                String errorMessage = TextKit.blankToNull(result.stderrText()) == null
                        ? "处理失败，执行引擎未返回错误详情" : result.stderrText();
                task.setResultText(taskContentService.write(taskId, "result", "执行失败：" + errorMessage));
                task.setResultData(null);
            }
            task.setRuntimeSessionId(result.session() == null ? null : result.session().sessionId());
            task.setRuntimeSessionPath(result.session() == null ? null : result.session().sessionPath());
            task.setEngineCompletedAt(result.engineCompletedAt());
            if (result.usage() != null) {
                task.setRequestCount(result.usage().requestCount());
                task.setInputTokens(result.usage().inputTokens());
                task.setCachedInputTokens(result.usage().cachedInputTokens());
                task.setCacheCreationInputTokens(result.usage().cacheCreationInputTokens());
                task.setOutputTokens(result.usage().outputTokens());
                task.setReasoningOutputTokens(result.usage().reasoningOutputTokens());
                task.setTotalTokens(result.usage().totalTokens());
            }
            task.setEndedAt(LocalDateTime.now());
            taskMetricsService.captureExecutionMetrics(task);
            task.setUpdatedAt(task.getEndedAt());
            AgentTaskEntity savedTask = agentTaskRepository.save(task);
            touchConversationRoot(savedTask, savedTask.getUpdatedAt());
            return savedTask;
        });
        if (saved == null) {
            return;
        }
        log.info("任务执行完成 taskId={} status={} exitCode={}", taskId, saved.getStatus(), result.exitCode());
        taskEventStreamService.complete(taskId);
    }

    public void fail(Long taskId, String message) {
        String errorMessage = TextKit.blankToNull(message) == null ? "处理失败，未返回错误详情" : message;
        Boolean failed = transactionTemplate.execute(status -> {
            AgentTaskEntity task = requireEntity(taskId);
            if (isTerminal(task.getStatus())) {
                return false;
            }
            task.setStatus(TaskStatus.FAILED);
            task.setStderrText(taskContentService.write(taskId, "stderr", errorMessage));
            task.setResultText(taskContentService.write(taskId, "result", "执行失败：" + errorMessage));
            task.setEndedAt(LocalDateTime.now());
            task.setUpdatedAt(task.getEndedAt());
            AgentTaskEntity savedTask = agentTaskRepository.save(task);
            touchConversationRoot(savedTask, savedTask.getUpdatedAt());
            return true;
        });
        if (Boolean.TRUE.equals(failed)) {
            appendEvent(taskId, failureEvent(errorMessage));
            captureTerminalMetrics(taskId);
            log.info("任务标记失败 taskId={} message={}", taskId, errorMessage);
            taskEventStreamService.complete(taskId);
        }
    }

    private void stopForUsageLimit(Long taskId) {
        Boolean stopped = transactionTemplate.execute(status -> {
            AgentTaskEntity task = requireEntity(taskId);
            if (isTerminal(task.getStatus())) {
                return false;
            }
            task.setStatus(TaskStatus.FAILED);
            task.setStderrText(taskContentService.write(taskId, "stderr", USAGE_LIMIT_MESSAGE));
            task.setResultText(taskContentService.write(taskId, "result", "执行终止：" + USAGE_LIMIT_MESSAGE));
            task.setEndedAt(LocalDateTime.now());
            task.setUpdatedAt(task.getEndedAt());
            AgentTaskEntity saved = agentTaskRepository.save(task);
            touchConversationRoot(saved, saved.getUpdatedAt());
            return true;
        });
        if (!Boolean.TRUE.equals(stopped)) {
            return;
        }
        appendEvent(taskId, usageLimitEvent());
        captureTerminalMetrics(taskId);
        taskRuntimeService.cancel(taskId);
        taskEventStreamService.complete(taskId);
        log.info("任务达到用户月度 Token 配额，已终止 taskId={}", taskId);
    }

    private TaskExecutionEvent usageLimitEvent() {
        TaskEventPayload payload = new TaskEventPayload();
        payload.setMessage(USAGE_LIMIT_MESSAGE);
        TaskExecutionEvent event = new TaskExecutionEvent();
        event.setType(TaskEventType.ERROR);
        event.setStatus(TaskEventStatus.FAILED);
        event.setTitle("已达到用量上限");
        event.setDetail(USAGE_LIMIT_MESSAGE);
        event.setPayload(payload);
        return event;
    }

    private boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.SUCCESS || status == TaskStatus.FAILED || status == TaskStatus.CANCELED;
    }

    private TaskExecutionEvent failureEvent(String message) {
        TaskEventPayload payload = new TaskEventPayload();
        payload.setMessage(message);
        TaskExecutionEvent event = new TaskExecutionEvent();
        event.setType(TaskEventType.ERROR);
        event.setStatus(TaskEventStatus.FAILED);
        event.setTitle("处理失败");
        event.setDetail(message);
        event.setPayload(payload);
        return event;
    }

    /**
     * 在终态事件落库后固化执行指标。
     *
     * @param taskId 已结束的任务 ID
     * @return 无返回值
     */
    private void captureTerminalMetrics(Long taskId) {
        transactionTemplate.executeWithoutResult(status -> {
            AgentTaskEntity task = requireEntity(taskId);
            taskMetricsService.captureExecutionMetrics(task);
            agentTaskRepository.save(task);
        });
    }

    private AgentTaskEntity requireEntity(Long id) {
        return agentTaskRepository.findWithDetailsById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.TASK_NOT_FOUND));
    }

    private Set<String> recommendedScenarioCodes(AgentTaskEntity task) {
        return task.getScenarioCode() == null
                ? Set.of() : moduleDefinitionService.scenarioRecommendationCodes(task.getScenarioCode());
    }

    private void touchConversationRoot(AgentTaskEntity task, LocalDateTime updatedAt) {
        Long rootId = task.getConversationRootTaskId();
        if (rootId == null || rootId.equals(task.getId())) {
            return;
        }
        agentTaskRepository.touchConversationRootUpdatedAt(rootId, updatedAt == null ? LocalDateTime.now() : updatedAt);
    }

    private int maxConcurrency() {
        try {
            return Math.max(runtimeConfigService.taskConcurrency(), 1);
        } catch (Exception e) {
            return Math.max(lingxiProperties.getTask().getWorkerCount(), 1);
        }
    }

    private void recoverUnfinishedTasks() {
        try {
            Integer recoveredRunningCount = transactionTemplate.execute(status -> {
                int running = agentTaskRepository.updateStatus(TaskStatus.RUNNING, TaskStatus.PENDING);
                int waiting = agentTaskRepository.updateStatus(TaskStatus.WAITING_USER, TaskStatus.PENDING);
                return running + waiting;
            });
            int pendingCount = enqueuePendingTasks();
            log.info("恢复未完成任务 recoveredRunningCount={} pendingCount={}", recoveredRunningCount, pendingCount);
        } catch (Exception e) {
            log.info("恢复未完成任务失败 message={}", e.getMessage());
        }
    }

    private int enqueuePendingTasks() {
        List<PendingTask> tasks = transactionTemplate.execute(status -> agentTaskRepository.findByStatusOrderByCreatedAtAsc(TaskStatus.PENDING)
                .stream()
                .map(task -> new PendingTask(task.getId(), taskPriority(task), task.getModelProfileId()))
                .toList());
        if (tasks == null) {
            return 0;
        }
        synchronized (this) {
            tasks.forEach(task -> enqueueTask(task.id(), task.priority(), task.modelProfileId()));
        }
        schedule();
        return tasks.size();
    }

    private void maintainQueueSafely() {
        try {
            enqueuePendingTasks();
            List<QueueNotice> notices;
            synchronized (this) {
                notices = dueQueueNotices(false);
            }
            notices.forEach(this::publishQueueNotice);
        } catch (Exception e) {
            log.info("维护任务队列失败 message={}", e.getMessage());
        }
    }

    private List<QueueNotice> dueQueueNotices(boolean force) {
        long now = System.currentTimeMillis();
        List<QueueNotice> notices = new ArrayList<>();
        int pendingIndex = 0;
        for (Long taskId : pendingTasks) {
            int aheadCount = runningTasks.size() + pendingIndex;
            pendingIndex++;
            if (aheadCount <= 0) {
                continue;
            }
            Long lastAt = lastQueueNoticeAt.get(taskId);
            if (!force && lastAt != null && now - lastAt < QUEUE_NOTICE_INTERVAL_MILLIS) {
                continue;
            }
            lastQueueNoticeAt.put(taskId, now);
            notices.add(new QueueNotice(taskId, aheadCount));
        }
        return notices;
    }

    private void publishQueueNotice(QueueNotice notice) {
        synchronized (this) {
            if (!pendingTasks.contains(notice.taskId())) {
                return;
            }
        }
        try {
            appendEvent(notice.taskId(), queueEvent(notice.aheadCount()));
        } catch (Exception e) {
            log.info("发布排队提示失败 taskId={} message={}", notice.taskId(), e.getMessage());
        }
    }

    private TaskExecutionEvent queueEvent(int aheadCount) {
        TaskExecutionEvent event = new TaskExecutionEvent();
        event.setType(TaskEventType.SYSTEM);
        event.setStatus(TaskEventStatus.INFO);
        event.setTitle("排队中，前方还有 " + aheadCount + " 项请求，请耐心等待");
        return event;
    }

    private List<Long> drainStartableTasks() {
        RuntimeConcurrencyPolicy policy = concurrencyPolicy();
        int maxConcurrency = policy.globalLimit();
        List<Long> taskIds = new ArrayList<>();
        Map<String, Integer> providerRunningCounts = new HashMap<>();
        Map<String, Integer> modelRunningCounts = new HashMap<>();
        runningTasks.forEach(taskId -> policy.acquire(
                taskModelProfileIds.get(taskId), providerRunningCounts, modelRunningCounts));
        Deque<Long> remainingTasks = new ArrayDeque<>();
        for (Long taskId : pendingTasks) {
            String modelProfileId = taskModelProfileIds.get(taskId);
            if (runningTasks.size() >= maxConcurrency
                    || !policy.allows(modelProfileId, providerRunningCounts, modelRunningCounts)) {
                remainingTasks.addLast(taskId);
                continue;
            }
            runningTasks.add(taskId);
            policy.acquire(modelProfileId, providerRunningCounts, modelRunningCounts);
            lastQueueNoticeAt.remove(taskId);
            taskQueuePriorities.remove(taskId);
            taskIds.add(taskId);
        }
        pendingTasks.clear();
        pendingTasks.addAll(remainingTasks);
        if (!taskIds.isEmpty()) {
            log.info("任务批量出队开始执行 taskIds={} pendingCount={} runningCount={} maxConcurrency={}",
                    taskIds, pendingTasks.size(), runningTasks.size(), maxConcurrency);
        }
        return taskIds;
    }

    private void enqueueTask(Long taskId, int priority, String modelProfileId) {
        if (pendingTasks.contains(taskId) || runningTasks.contains(taskId)) {
            return;
        }
        taskQueuePriorities.put(taskId, priority);
        taskModelProfileIds.put(taskId, modelProfileId);
        insertPendingTask(taskId, priority);
    }

    private void insertPendingTask(Long taskId, int priority) {
        if (pendingTasks.isEmpty()) {
            pendingTasks.addLast(taskId);
            return;
        }
        Deque<Long> reordered = new ArrayDeque<>();
        boolean inserted = false;
        for (Long pendingTaskId : pendingTasks) {
            if (!inserted && priority < taskQueuePriorities.getOrDefault(pendingTaskId, USER_TASK_PRIORITY)) {
                reordered.addLast(taskId);
                inserted = true;
            }
            reordered.addLast(pendingTaskId);
        }
        if (!inserted) {
            reordered.addLast(taskId);
        }
        pendingTasks.clear();
        pendingTasks.addAll(reordered);
    }

    private TaskQueueMetadata taskQueueMetadata(Long taskId) {
        try {
            return agentTaskRepository.findById(taskId)
                    .map(task -> new TaskQueueMetadata(taskPriority(task), task.getModelProfileId()))
                    .orElse(new TaskQueueMetadata(USER_TASK_PRIORITY, null));
        } catch (Exception e) {
            log.info("读取任务队列优先级失败，按普通任务处理 taskId={} message={}", taskId, e.getMessage());
            return new TaskQueueMetadata(USER_TASK_PRIORITY, null);
        }
    }

    private RuntimeConcurrencyPolicy concurrencyPolicy() {
        try {
            return runtimeConfigService.concurrencyPolicy();
        } catch (Exception e) {
            int fallbackLimit = Math.max(lingxiProperties.getTask().getWorkerCount(), 1);
            return new RuntimeConcurrencyPolicy(fallbackLimit, Map.of(), Map.of(), Map.of());
        }
    }

    private int taskPriority(AgentTaskEntity task) {
        if (task.getQueuePriority() != null) {
            return task.getQueuePriority();
        }
        return USER_TASK_PRIORITY;
    }

    private String resultFormat(AgentTaskEntity task) {
        if (task.getResultFormat() != null) {
            return task.getResultFormat();
        }
        return "markdown-sections";
    }

    /**
     * 读取当前任务定义声明的结果渲染器。
     *
     * @param task 当前执行任务
     * @return 结果渲染器标识，未配置时返回 null
     */
    private String resultRenderer(AgentTaskEntity task) {
        return task.getResultRenderer();
    }

    private record PendingTask(Long id, int priority, String modelProfileId) {
    }

    private record TaskQueueMetadata(int priority, String modelProfileId) {
    }

    private record QueueNotice(Long taskId, int aheadCount) {
    }

    @PreDestroy
    public void shutdown() {
        taskExecutor.shutdownNow();
        queueMaintenanceExecutor.shutdownNow();
    }
}
