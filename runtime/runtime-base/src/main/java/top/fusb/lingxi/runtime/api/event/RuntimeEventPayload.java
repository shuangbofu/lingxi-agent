package top.fusb.lingxi.runtime.api.event;

import java.util.Map;

public record RuntimeEventPayload(
        String rawType,
        String itemType,
        String itemId,
        String status,
        String command,
        String toolName,
        String callId,
        String arguments,
        String output,
        String message,
        Integer exitCode,
        String actionKey,
        String actionInstanceId,
        String actionLabel,
        String actionTarget,
        Boolean transientEvent,
        Map<String, String> metrics,
        String actionGroupId,
        Integer actionGroupSize,
        RuntimeEventSemantic semantic,
        RuntimeEventVisibility visibility,
        RuntimeModelTimingMode modelTimingMode,
        RuntimeActionIcon actionIcon
) {

    public RuntimeEventPayload(String rawType, String itemType, String itemId, String status, String command,
                               String toolName, String callId, String arguments, String output, String message,
                               Integer exitCode, String actionKey, String actionInstanceId, String actionLabel,
                               String actionTarget, Boolean transientEvent, Map<String, String> metrics,
                               String actionGroupId, Integer actionGroupSize, RuntimeEventSemantic semantic,
                               RuntimeEventVisibility visibility, RuntimeModelTimingMode modelTimingMode) {
        this(rawType, itemType, itemId, status, command, toolName, callId, arguments, output, message,
                exitCode, actionKey, actionInstanceId, actionLabel, actionTarget, transientEvent,
                metrics, actionGroupId, actionGroupSize, semantic, visibility, modelTimingMode, null);
    }

    public RuntimeEventPayload(String rawType, String itemType, String itemId, String status, String command,
                               String toolName, String callId, String arguments, String output, String message,
                               Integer exitCode, String actionKey, String actionInstanceId, String actionLabel,
                               String actionTarget, Boolean transientEvent) {
        this(rawType, itemType, itemId, status, command, toolName, callId, arguments, output, message,
                exitCode, actionKey, actionInstanceId, actionLabel, actionTarget, transientEvent,
                null, null, null, null, RuntimeEventVisibility.PUBLIC, null);
    }

    /**
     * 创建不包含动作分组的兼容事件载荷。
     *
     * @param rawType Runtime 原始事件类型
     * @param itemType 事件项类型
     * @param itemId 事件项标识
     * @param status 原始状态
     * @param command 命令
     * @param toolName 工具名称
     * @param callId 调用标识
     * @param arguments 调用参数
     * @param output 执行输出
     * @param message 消息
     * @param exitCode 退出码
     * @param actionKey 动作类型标识
     * @param actionInstanceId 动作实例标识
     * @param actionLabel 动作展示名称
     * @param actionTarget 动作目标
     * @param transientEvent 是否为瞬时事件
     * @param metrics 指标数据
     */
    public RuntimeEventPayload(String rawType, String itemType, String itemId, String status, String command,
                               String toolName, String callId, String arguments, String output, String message,
                               Integer exitCode, String actionKey, String actionInstanceId, String actionLabel,
                               String actionTarget, Boolean transientEvent, Map<String, String> metrics) {
        this(rawType, itemType, itemId, status, command, toolName, callId, arguments, output, message,
                exitCode, actionKey, actionInstanceId, actionLabel, actionTarget, transientEvent,
                metrics, null, null, null, RuntimeEventVisibility.PUBLIC, null);
    }

    public RuntimeEventPayload(String rawType, String itemType, String itemId, String status, String command,
                               String toolName, String callId, String arguments, String output, String message,
                               Integer exitCode, String actionKey, String actionInstanceId, String actionLabel,
                               String actionTarget, Boolean transientEvent, Map<String, String> metrics,
                               String actionGroupId, Integer actionGroupSize) {
        this(rawType, itemType, itemId, status, command, toolName, callId, arguments, output, message,
                exitCode, actionKey, actionInstanceId, actionLabel, actionTarget, transientEvent,
                metrics, actionGroupId, actionGroupSize, null, RuntimeEventVisibility.PUBLIC, null);
    }

    /**
     * 在保留事件原始语义的同时附加 Runtime 识别出的动作分组。
     *
     * @param groupId Runtime 内唯一的动作组标识
     * @param groupSize 动作组预期包含的动作数量
     * @return 带动作分组的新事件载荷
     */
    public RuntimeEventPayload withActionGroup(String groupId, Integer groupSize) {
        return new RuntimeEventPayload(
                rawType, itemType, itemId, status, command, toolName, callId, arguments, output, message,
                exitCode, actionKey, actionInstanceId, actionLabel, actionTarget, transientEvent,
                metrics, groupId, groupSize, semantic, visibility, modelTimingMode, actionIcon);
    }

    /**
     * 保留动作身份和分组信息，更新动作的终止状态与输出。
     *
     * @param value Runtime 原始终止状态
     * @param result 动作终止原因或执行结果
     * @return 带终止状态和输出的新事件载荷
     */
    public RuntimeEventPayload withStatusAndOutput(String value, String result) {
        return new RuntimeEventPayload(
                rawType, itemType, itemId, value, command, toolName, callId, arguments, result, message,
                exitCode, actionKey, actionInstanceId, actionLabel, actionTarget, transientEvent,
                metrics, actionGroupId, actionGroupSize, semantic, visibility, modelTimingMode, actionIcon);
    }

    /**
     * 为 Runtime 原始事件附加平台可消费的统一语义。
     *
     * @param value 与原始协议无关的事件语义
     * @return 保留原始诊断字段并附加统一语义的新载荷
     */
    public RuntimeEventPayload withSemantic(RuntimeEventSemantic value) {
        return new RuntimeEventPayload(
                rawType, itemType, itemId, status, command, toolName, callId, arguments, output, message,
                exitCode, actionKey, actionInstanceId, actionLabel, actionTarget, transientEvent,
                metrics, actionGroupId, actionGroupSize, value, visibility, modelTimingMode, actionIcon);
    }

    /**
     * 标记事件是否应进入面向用户的事件流。
     *
     * @param value 统一事件可见性
     * @return 带可见性的新载荷
     */
    public RuntimeEventPayload withVisibility(RuntimeEventVisibility value) {
        return new RuntimeEventPayload(
                rawType, itemType, itemId, status, command, toolName, callId, arguments, output, message,
                exitCode, actionKey, actionInstanceId, actionLabel, actionTarget, transientEvent,
                metrics, actionGroupId, actionGroupSize, semantic,
                value == null ? RuntimeEventVisibility.PUBLIC : value, modelTimingMode, actionIcon);
    }

    /**
     * 标记 Runtime 提供的模型计时数据采用哪种采集方式。
     *
     * @param value 模型计时采集方式
     * @return 带模型计时方式的新事件载荷
     */
    public RuntimeEventPayload withModelTimingMode(RuntimeModelTimingMode value) {
        return new RuntimeEventPayload(
                rawType, itemType, itemId, status, command, toolName, callId, arguments, output, message,
                exitCode, actionKey, actionInstanceId, actionLabel, actionTarget, transientEvent,
                metrics, actionGroupId, actionGroupSize, semantic, visibility, value, actionIcon);
    }

    /**
     * 为动作附加与具体前端图标库无关的展示语义。
     *
     * @param value 通用动作图标语义
     * @return 带图标语义的新事件载荷
     */
    public RuntimeEventPayload withActionIcon(RuntimeActionIcon value) {
        return new RuntimeEventPayload(
                rawType, itemType, itemId, status, command, toolName, callId, arguments, output, message,
                exitCode, actionKey, actionInstanceId, actionLabel, actionTarget, transientEvent,
                metrics, actionGroupId, actionGroupSize, semantic, visibility, modelTimingMode, value);
    }
}
