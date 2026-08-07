package top.fusb.lingxi.runtime.api.model;

/**
 * Runtime 在完整 Agent 消息完成前产生的真实文本增量。
 *
 * @param messageId 当前消息在本轮执行中的稳定标识
 * @param delta 本次新增文本
 * @param type 增量文本的通用语义类型
 */
public record RuntimeMessageDelta(
        String messageId,
        String delta,
        RuntimeMessageDeltaType type
) {

    public RuntimeMessageDelta(String messageId, String delta) {
        this(messageId, delta, RuntimeMessageDeltaType.MESSAGE);
    }

    public RuntimeMessageDelta {
        type = type == null ? RuntimeMessageDeltaType.MESSAGE : type;
    }
}
