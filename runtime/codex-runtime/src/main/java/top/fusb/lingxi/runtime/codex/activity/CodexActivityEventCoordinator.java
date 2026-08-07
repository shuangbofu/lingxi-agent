package top.fusb.lingxi.runtime.codex.activity;

import top.fusb.lingxi.runtime.api.event.RuntimeActionIcon;
import top.fusb.lingxi.runtime.api.event.RuntimeEvent;
import top.fusb.lingxi.runtime.api.event.RuntimeEventPayload;
import top.fusb.lingxi.runtime.api.event.RuntimeEventStatus;
import top.fusb.lingxi.runtime.api.event.RuntimeEventType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 根据 Codex 的 turn、工具和消息事件维护当前真实活动状态。
 */
public final class CodexActivityEventCoordinator {

    private final String executionId;
    private final Consumer<RuntimeEvent> eventConsumer;
    private final Set<String> runningActivities = new HashSet<>();
    private int anonymousRunningActivities;
    private long actionGroupSequence;
    private String currentActionGroupId;
    private int currentActionGroupSize;
    private final Set<String> nativeToolBatches = new HashSet<>();
    private final Map<String, String> activityGroupIds = new HashMap<>();
    private final Map<String, Integer> actionGroupSizes = new HashMap<>();
    private String nativeActionGroupId;
    private boolean waitingForModel;
    private boolean finished;

    public CodexActivityEventCoordinator(Consumer<RuntimeEvent> eventConsumer) {
        this("codex", eventConsumer);
    }

    /**
     * 创建带执行标识的 Codex 活动协调器。
     *
     * @param executionId 当前 Runtime 执行标识
     * @param eventConsumer Runtime 事件输出目标
     */
    public CodexActivityEventCoordinator(String executionId, Consumer<RuntimeEvent> eventConsumer) {
        this.executionId = executionId == null || executionId.isBlank() ? "codex" : executionId;
        this.eventConsumer = eventConsumer;
    }

    /**
     * 标记 Runtime 正在准备 Codex Home 和进程参数。
     *
     * @return 无返回值
     */
    public synchronized void preparing() {
        emitRunningState("codex.runtime.preparing", "准备执行引擎", RuntimeActionIcon.WRENCH);
    }

    /**
     * 标记 Runtime 已完成配置准备并正在启动 Codex CLI 进程。
     *
     * @return 无返回值
     */
    public synchronized void processStarting() {
        emitRunningState("codex.process.starting", "启动执行引擎", RuntimeActionIcon.PLAY_CIRCLE);
    }

    /**
     * 标记 Codex 已向模型发起一次真实请求，且正在等待模型返回可处理的内容。
     *
     * @return 无返回值
     */
    public synchronized void modelRequestStarted() {
        if (finished || hasRunningActivity()) {
            return;
        }
        emitThinking();
    }

    /**
     * 接收任一 Codex 事件源产生的事件，并按真实控制权状态补充临时思考事件。
     *
     * @param event stdout 或 session JSONL 解析出的 Codex 事件
     * @return 无返回值
     */
    public synchronized void accept(RuntimeEvent event) {
        if (event == null || finished) {
            return;
        }
        if (isTurnStarted(event)) {
            eventConsumer.accept(event);
            if (!hasRunningActivity()) {
                emitThinking();
            }
            return;
        }
        if (isTerminal(event)) {
            waitingForModel = false;
            runningActivities.clear();
            anonymousRunningActivities = 0;
            nativeToolBatches.clear();
            activityGroupIds.clear();
            actionGroupSizes.clear();
            nativeActionGroupId = null;
            eventConsumer.accept(event);
            emitRunningState("codex.run.finishing", "结束本轮运行", RuntimeActionIcon.CHECK_CIRCLE);
            return;
        }
        if (event.type() == RuntimeEventType.AGENT_MESSAGE) {
            waitingForModel = false;
            eventConsumer.accept(event);
            if (!hasRunningActivity()) {
                emitRunningState("codex.response.processing", "处理模型响应", RuntimeActionIcon.WRENCH);
            }
            return;
        }
        if (isActivity(event)) {
            acceptActivity(event);
            return;
        }
        if (event.type() == RuntimeEventType.THINKING) {
            waitingForModel = event.status() == RuntimeEventStatus.RUNNING;
            eventConsumer.accept(event);
            return;
        }
        eventConsumer.accept(event);
        if (waitingForModel && !hasRunningActivity()) {
            emitThinking();
        }
    }

    /**
     * 在 agent_message 已到达但尚未提交为完整事件时，展示真实的响应处理状态。
     *
     * @return 无返回值
     */
    public synchronized void modelResponseStarted() {
        if (finished || hasRunningActivity()) {
            return;
        }
        waitingForModel = false;
        emitRunningState("codex.response.processing", "处理模型响应", RuntimeActionIcon.WRENCH);
    }

    /**
     * 使用 Codex session 中的外层工具调用作为内部工具批次边界。
     *
     * @param callId 外层工具调用标识
     * @param modelRequestId 产生该批工具调用的模型请求标识
     * @return 无返回值
     */
    public synchronized void toolBatchStarted(String callId, String modelRequestId) {
        if (finished || callId == null || callId.isBlank()) {
            return;
        }
        if (nativeToolBatches.isEmpty()) {
            nativeActionGroupId = executionId + ":" + (modelRequestId == null || modelRequestId.isBlank()
                    ? "tool-batch-" + (++actionGroupSequence) : modelRequestId);
            currentActionGroupId = nativeActionGroupId;
            currentActionGroupSize = 0;
        }
        nativeToolBatches.add(callId);
        waitingForModel = false;
    }

    /**
     * 结束 Codex session 中的外层工具批次，恢复模型等待状态。
     *
     * @param callId 已完成的外层工具调用标识
     * @return 无返回值
     */
    public synchronized void toolBatchCompleted(String callId) {
        if (callId == null || !nativeToolBatches.remove(callId) || !nativeToolBatches.isEmpty()) {
            return;
        }
        String completedGroupId = nativeActionGroupId;
        nativeActionGroupId = null;
        if (!hasRunningActivity()) {
            actionGroupSizes.remove(completedGroupId);
            currentActionGroupId = null;
            currentActionGroupSize = 0;
            emitThinking();
        }
    }

    /**
     * 在 Codex 进程退出且所有事件读取结束后清理临时等待状态。
     *
     * @return 无返回值
     */
    public synchronized void finish() {
        if (finished) {
            return;
        }
        finished = true;
        runningActivities.clear();
        anonymousRunningActivities = 0;
        nativeToolBatches.clear();
        activityGroupIds.clear();
        actionGroupSizes.clear();
        nativeActionGroupId = null;
        waitingForModel = false;
        emitRunningState("codex.result.finalizing", "整理运行结果", RuntimeActionIcon.CHECK_CIRCLE);
    }

    private void acceptActivity(RuntimeEvent event) {
        String activityId = activityId(event);
        if (event.status() == RuntimeEventStatus.RUNNING) {
            waitingForModel = false;
            if (nativeActionGroupId != null) {
                currentActionGroupId = nativeActionGroupId;
            } else if (!hasRunningActivity()) {
                currentActionGroupId = executionId + ":action-group-" + (++actionGroupSequence);
                currentActionGroupSize = 0;
            }
            currentActionGroupSize++;
            actionGroupSizes.put(currentActionGroupId, currentActionGroupSize);
            if (activityId == null) {
                anonymousRunningActivities++;
            } else {
                runningActivities.add(activityId);
                activityGroupIds.put(activityId, currentActionGroupId);
            }
            eventConsumer.accept(withActionGroup(event, currentActionGroupId, currentActionGroupSize));
            return;
        }
        if (event.status() == RuntimeEventStatus.SUCCESS || event.status() == RuntimeEventStatus.FAILED) {
            String activityGroupId = activityId == null ? currentActionGroupId : activityGroupIds.remove(activityId);
            int activityGroupSize = actionGroupSizes.getOrDefault(activityGroupId, currentActionGroupSize);
            if (activityId == null) {
                anonymousRunningActivities = Math.max(0, anonymousRunningActivities - 1);
            } else {
                runningActivities.remove(activityId);
            }
            eventConsumer.accept(withActionGroup(event, activityGroupId, activityGroupSize));
            if (!hasRunningActivity() && nativeToolBatches.isEmpty()) {
                emitThinking();
                actionGroupSizes.remove(currentActionGroupId);
                currentActionGroupId = null;
                currentActionGroupSize = 0;
            }
            return;
        }
        eventConsumer.accept(event);
    }

    private RuntimeEvent withActionGroup(RuntimeEvent event, String actionGroupId, int actionGroupSize) {
        if (actionGroupId == null || event.payload() == null) {
            return event;
        }
        return new RuntimeEvent(
                event.type(), event.status(), event.title(), event.detail(),
                event.payload().withActionGroup(actionGroupId, actionGroupSize));
    }

    private void emitThinking() {
        waitingForModel = true;
        eventConsumer.accept(new RuntimeEvent(
                RuntimeEventType.THINKING,
                RuntimeEventStatus.RUNNING,
                "codex.reasoning",
                null,
                transientPayload()
        ));
    }

    private void emitRunningState(String stateKey, String label, RuntimeActionIcon icon) {
        eventConsumer.accept(new RuntimeEvent(
                RuntimeEventType.SYSTEM,
                RuntimeEventStatus.RUNNING,
                stateKey,
                null,
                new RuntimeEventPayload(
                        stateKey, "runtime_state", stateKey, "running", null, null, null, null,
                        null, null, null, stateKey, stateKey, label, null, true
                ).withActionIcon(icon)
        ));
    }

    private RuntimeEventPayload transientPayload() {
        return new RuntimeEventPayload(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, true
        );
    }

    private boolean isTurnStarted(RuntimeEvent event) {
        return event.type() == RuntimeEventType.SYSTEM
                && ("codex.turn.started".equals(event.title()) || "codex.task.started".equals(event.title()));
    }

    private boolean isTerminal(RuntimeEvent event) {
        if (event.type() == RuntimeEventType.ERROR) {
            return true;
        }
        return event.type() == RuntimeEventType.SYSTEM
                && ("codex.turn.completed".equals(event.title()) || "codex.task.completed".equals(event.title()));
    }

    private boolean isActivity(RuntimeEvent event) {
        return event.type() != RuntimeEventType.SYSTEM
                && event.type() != RuntimeEventType.THINKING
                && event.type() != RuntimeEventType.AGENT_MESSAGE
                && event.type() != RuntimeEventType.METRIC
                && event.type() != RuntimeEventType.ERROR;
    }

    private boolean hasRunningActivity() {
        return anonymousRunningActivities > 0 || !runningActivities.isEmpty();
    }

    private String activityId(RuntimeEvent event) {
        RuntimeEventPayload payload = event.payload();
        if (payload == null) {
            return null;
        }
        if (payload.actionInstanceId() != null && !payload.actionInstanceId().isBlank()) {
            return payload.actionInstanceId();
        }
        if (payload.callId() != null && !payload.callId().isBlank()) {
            return payload.callId();
        }
        return null;
    }
}
