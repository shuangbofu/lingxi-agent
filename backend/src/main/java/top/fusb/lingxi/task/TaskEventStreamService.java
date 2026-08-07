package top.fusb.lingxi.task;

import top.fusb.lingxi.dto.TaskEventResponse;
import top.fusb.lingxi.dto.TaskExecutionEvent;
import top.fusb.lingxi.dto.TaskMessageDeltaResponse;
import top.fusb.lingxi.dto.TokenUsageSnapshot;
import top.fusb.lingxi.enums.TaskStatus;
import top.fusb.lingxi.repository.AgentTaskRepository;
import top.fusb.lingxi.runtime.api.model.RuntimeMessageDelta;
import top.fusb.lingxi.runtime.api.model.RuntimeMessageDeltaType;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskEventStreamService {

    private static final long STREAM_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final int MAX_TOTAL_CONNECTIONS = 100;
    private static final int MAX_TASK_CONNECTIONS = 5;

    private final TaskEventService taskEventService;
    private final AgentTaskRepository agentTaskRepository;
    private final ConcurrentHashMap<Long, Set<TaskEventConnection>> emittersByTaskId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, LiveMessage>> liveMessagesByTaskId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, TaskEventResponse> currentTransientEventsByTaskId = new ConcurrentHashMap<>();
    private final AtomicInteger totalConnections = new AtomicInteger();
    private final AtomicLong transientEventSequence = new AtomicLong();

    /**
     * 建立任务事件 SSE 连接，连接时只补发一次历史事件，之后由任务写事件时主动推送。
     *
     * @param taskId 任务 ID
     * @param afterId 前端已收到的最后事件 ID
     * @param includeProcessOutput 是否包含命令输出、参数和完整详情入口
     * @param includeTaskEvents 是否补发并持续推送思考过程事件
     * @param connectionId 前端页面生命周期内稳定的连接 ID，用于重连时替换失效连接
     * @return SSE emitter
     */
    public SseEmitter stream(Long taskId, Long afterId, boolean includeProcessOutput, boolean includeTaskEvents,
                             String connectionId) {
        Set<TaskEventConnection> existingConnections = emittersByTaskId.get(taskId);
        if (existingConnections != null) {
            replaceConnection(taskId, existingConnections, connectionId);
        }
        Set<TaskEventConnection> taskEmitters = emittersByTaskId.computeIfAbsent(
                taskId, key -> ConcurrentHashMap.newKeySet());
        if (totalConnections.get() >= MAX_TOTAL_CONNECTIONS) {
            log.info("任务事件 SSE 连接超过全局上限 taskId={} total={}", taskId, totalConnections.get());
            return rejectedEmitter("任务事件连接超过全局上限");
        }
        if (taskEmitters.size() >= MAX_TASK_CONNECTIONS) {
            log.info("任务事件 SSE 连接超过任务上限 taskId={} count={}", taskId, taskEmitters.size());
            return rejectedEmitter("任务事件连接超过任务上限");
        }

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        TaskEventConnection connection = new TaskEventConnection(
                connectionId, emitter, includeProcessOutput, includeTaskEvents);
        register(taskId, connection, taskEmitters);
        if (includeTaskEvents) {
            replay(taskId, afterId, connection);
            replayCurrentTransient(taskId, connection);
            replayLiveMessages(taskId, connection);
        }
        if (isFinished(taskId)) {
            emitter.complete();
        }
        return emitter;
    }

    /**
     * 推送新写入的任务事件。
     *
     * @param taskId 任务 ID
     * @param event 任务事件
     * @return 无返回值
     */
    public void publish(Long taskId, TaskEventResponse event) {
        if (event == null) {
            return;
        }
        currentTransientEventsByTaskId.remove(taskId);
        Set<TaskEventConnection> emitters = emittersByTaskId.get(taskId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (TaskEventConnection connection : List.copyOf(emitters)) {
            if (!connection.includeTaskEvents()) {
                continue;
            }
            send(taskId, connection, connection.includeProcessOutput() ? event : taskEventService.maskProcessOutput(event));
        }
    }

    /**
     * 推送任务执行期间累计的模型用量，不将运行快照写成思考过程事件。
     *
     * @param taskId 任务 ID
     * @param usage 当前任务累计用量
     * @return 无返回值
     */
    public void publishUsage(Long taskId, TokenUsageSnapshot usage) {
        if (usage == null) {
            return;
        }
        Set<TaskEventConnection> emitters = emittersByTaskId.get(taskId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (TaskEventConnection connection : List.copyOf(emitters)) {
            sendUsage(taskId, connection, usage);
        }
    }

    /**
     * 推送只服务于当前页面反馈的临时事件，并保留当前状态供晚建立或重连的 SSE 回放。
     *
     * @param taskId 任务 ID
     * @param event 临时任务事件
     * @return 无返回值
     */
    public void publishTransient(Long taskId, TaskExecutionEvent event) {
        if (event == null) {
            return;
        }
        TaskEventResponse response = new TaskEventResponse();
        response.setId(-transientEventSequence.incrementAndGet());
        response.setType(event.getType().name());
        response.setStatus(event.getStatus().name());
        response.setTitle(event.getTitle());
        response.setDetail(event.getDetail());
        response.setDetailFile(false);
        response.setPayload(event.getPayload());
        response.setCreatedAt(LocalDateTime.now());
        currentTransientEventsByTaskId.put(taskId, response);
        Set<TaskEventConnection> emitters = emittersByTaskId.get(taskId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (TaskEventConnection connection : List.copyOf(emitters)) {
            if (!connection.includeTaskEvents()) {
                continue;
            }
            send(taskId, connection, connection.includeProcessOutput()
                    ? response : taskEventService.maskProcessOutput(response));
        }
    }

    /**
     * 推送 Agent 返回的真实消息增量，并保留当前累计文本供 SSE 重连恢复。
     *
     * @param taskId 任务 ID
     * @param messageDelta Runtime 消息增量
     * @return 无返回值
     */
    public void publishMessageDelta(Long taskId, RuntimeMessageDelta messageDelta) {
        if (messageDelta == null || messageDelta.messageId() == null || messageDelta.messageId().isBlank()
                || messageDelta.delta() == null || messageDelta.delta().isEmpty()) {
            return;
        }
        liveMessagesByTaskId.computeIfAbsent(taskId, ignored -> new ConcurrentHashMap<>())
                .merge(messageDelta.messageId(), new LiveMessage(messageDelta.type(), messageDelta.delta()),
                        LiveMessage::append);
        sendMessageDelta(taskId, new TaskMessageDeltaResponse(
                messageDelta.messageId(), messageDelta.delta(), null, messageDelta.type()));
    }

    /**
     * 完整 Agent 消息落库后清理对应的实时草稿，保证 SSE 重连至少能恢复一种消息形态。
     *
     * @param taskId 任务 ID
     * @param messageId Runtime 消息 ID
     * @return 无返回值
     */
    public void completeMessage(Long taskId, String messageId) {
        ConcurrentHashMap<String, LiveMessage> messages = liveMessagesByTaskId.get(taskId);
        if (messages == null || messageId == null) {
            return;
        }
        messages.remove(messageId);
        if (messages.isEmpty()) {
            liveMessagesByTaskId.remove(taskId, messages);
        }
    }

    /**
     * 任务结束后关闭该任务下的所有 SSE 连接。
     *
     * @param taskId 任务 ID
     * @return 无返回值
     */
    public void complete(Long taskId) {
        liveMessagesByTaskId.remove(taskId);
        currentTransientEventsByTaskId.remove(taskId);
        Set<TaskEventConnection> emitters = emittersByTaskId.remove(taskId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (TaskEventConnection connection : emitters) {
            try {
                connection.emitter().send(SseEmitter.event()
                        .name("task-complete")
                        .data(taskId));
                connection.emitter().complete();
            } catch (Exception ignored) {
                // 浏览器可能已经关闭连接。
            } finally {
                totalConnections.updateAndGet(value -> Math.max(0, value - 1));
            }
        }
        log.info("任务事件 SSE 已关闭 taskId={} count={}", taskId, emitters.size());
    }

    private void register(Long taskId, TaskEventConnection connection, Set<TaskEventConnection> taskEmitters) {
        taskEmitters.add(connection);
        int total = totalConnections.incrementAndGet();
        Runnable cleanup = () -> unregister(taskId, connection);
        connection.emitter().onCompletion(cleanup);
        connection.emitter().onTimeout(cleanup);
        connection.emitter().onError(error -> cleanup.run());
        log.info("任务事件 SSE 已连接 taskId={} taskConnections={} totalConnections={}", taskId, taskEmitters.size(), total);
    }

    private void replaceConnection(Long taskId, Set<TaskEventConnection> taskEmitters, String connectionId) {
        for (TaskEventConnection connection : List.copyOf(taskEmitters)) {
            if (!connection.connectionId().equals(connectionId)) {
                continue;
            }
            unregister(taskId, connection);
            try {
                connection.emitter().complete();
            } catch (Exception ignored) {
                // 已失效的浏览器连接可能无法再次关闭。
            }
            log.info("任务事件 SSE 已替换旧连接 taskId={} connectionId={}", taskId, connectionId);
        }
    }

    private SseEmitter rejectedEmitter(String reason) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().name("stream-rejected").data(reason));
        } catch (IOException ignored) {
            // 拒绝连接时客户端可能已经离开。
        }
        emitter.complete();
        return emitter;
    }

    private void unregister(Long taskId, TaskEventConnection connection) {
        Set<TaskEventConnection> emitters = emittersByTaskId.get(taskId);
        if (emitters != null && emitters.remove(connection)) {
            int total = totalConnections.updateAndGet(value -> Math.max(0, value - 1));
            if (emitters.isEmpty()) {
                emittersByTaskId.remove(taskId, emitters);
            }
            log.info("任务事件 SSE 已断开 taskId={} totalConnections={}", taskId, total);
        }
    }

    private void replay(Long taskId, Long afterId, TaskEventConnection connection) {
        try {
            List<TaskEventResponse> events = taskEventService.listAfter(taskId, afterId == null ? 0L : afterId, connection.includeProcessOutput());
            for (TaskEventResponse event : events) {
                send(taskId, connection, event);
            }
            log.info("任务事件 SSE 历史补发完成 taskId={} count={}", taskId, events.size());
        } catch (Exception e) {
            log.info("任务事件 SSE 历史补发失败 taskId={} message={}", taskId, e.getMessage());
            connection.emitter().completeWithError(e);
        }
    }

    private void replayLiveMessages(Long taskId, TaskEventConnection connection) {
        ConcurrentHashMap<String, LiveMessage> messages = liveMessagesByTaskId.get(taskId);
        if (messages == null || messages.isEmpty()) {
            return;
        }
        messages.forEach((messageId, message) -> {
            if (message.type() == RuntimeMessageDeltaType.REASONING && !connection.includeProcessOutput()) {
                return;
            }
            sendMessageDelta(taskId, connection, new TaskMessageDeltaResponse(
                    messageId, null, message.content(), message.type()));
        });
    }

    private void replayCurrentTransient(Long taskId, TaskEventConnection connection) {
        TaskEventResponse current = currentTransientEventsByTaskId.get(taskId);
        if (current != null) {
            send(taskId, connection, connection.includeProcessOutput()
                    ? current : taskEventService.maskProcessOutput(current));
        }
    }

    private void send(Long taskId, TaskEventConnection connection, TaskEventResponse event) {
        SseEmitter emitter = connection.emitter();
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(event.getId()))
                    .name("task-event")
                    .data(event));
        } catch (IOException e) {
            log.info("任务事件 SSE 推送失败，关闭连接 taskId={} eventId={} message={}", taskId, event.getId(), e.getMessage());
            unregister(taskId, connection);
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 浏览器可能已经关闭连接。
            }
        } catch (Exception e) {
            log.info("任务事件 SSE 推送异常，关闭连接 taskId={} eventId={} message={}", taskId, event.getId(), e.getMessage());
            unregister(taskId, connection);
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
                // 浏览器可能已经关闭连接。
            }
        }
    }

    private void sendUsage(Long taskId, TaskEventConnection connection, TokenUsageSnapshot usage) {
        SseEmitter emitter = connection.emitter();
        try {
            emitter.send(SseEmitter.event()
                    .name("task-usage")
                    .data(usage));
        } catch (Exception e) {
            log.info("任务用量 SSE 推送失败，关闭连接 taskId={} message={}", taskId, e.getMessage());
            unregister(taskId, connection);
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 浏览器可能已经关闭连接。
            }
        }
    }

    private void sendMessageDelta(Long taskId, TaskMessageDeltaResponse messageDelta) {
        Set<TaskEventConnection> emitters = emittersByTaskId.get(taskId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (TaskEventConnection connection : List.copyOf(emitters)) {
            if (connection.includeTaskEvents()
                    && (messageDelta.getType() != RuntimeMessageDeltaType.REASONING
                    || connection.includeProcessOutput())) {
                sendMessageDelta(taskId, connection, messageDelta);
            }
        }
    }

    private void sendMessageDelta(Long taskId, TaskEventConnection connection, TaskMessageDeltaResponse messageDelta) {
        try {
            connection.emitter().send(SseEmitter.event()
                    .name("task-message-delta")
                    .data(messageDelta));
        } catch (Exception e) {
            log.info("任务消息增量 SSE 推送失败，关闭连接 taskId={} messageId={} message={}",
                    taskId, messageDelta.getMessageId(), e.getMessage());
            unregister(taskId, connection);
            try {
                connection.emitter().complete();
            } catch (Exception ignored) {
                // 浏览器可能已经关闭连接。
            }
        }
    }

    private boolean isFinished(Long taskId) {
        return agentTaskRepository.findById(taskId)
                .map(task -> task.getStatus() != TaskStatus.PENDING && task.getStatus() != TaskStatus.RUNNING && task.getStatus() != TaskStatus.WAITING_USER)
                .orElse(true);
    }

    private record LiveMessage(RuntimeMessageDeltaType type, String content) {

        private LiveMessage append(LiveMessage next) {
            RuntimeMessageDeltaType nextType = next.type() == null ? type : next.type();
            return new LiveMessage(nextType, content + next.content());
        }
    }

    @PreDestroy
    public void shutdown() {
        for (Long taskId : List.copyOf(emittersByTaskId.keySet())) {
            complete(taskId);
        }
    }

    private record TaskEventConnection(String connectionId, SseEmitter emitter, boolean includeProcessOutput,
                                       boolean includeTaskEvents) {
    }
}
