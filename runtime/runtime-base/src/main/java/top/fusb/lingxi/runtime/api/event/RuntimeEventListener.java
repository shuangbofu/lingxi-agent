package top.fusb.lingxi.runtime.api.event;

import top.fusb.lingxi.runtime.api.model.RuntimeMessageDelta;
import top.fusb.lingxi.runtime.api.model.RuntimeUsage;

public interface RuntimeEventListener {

    void onEvent(RuntimeEvent event);

    /**
     * 接收可选的 Agent 消息增量；只返回完整消息的 Runtime 无需实现。
     *
     * @param delta 本次真实文本增量
     * @return 无返回值
     */
    default void onMessageDelta(RuntimeMessageDelta delta) {
    }

    default void onUsage(RuntimeUsage usage) {
    }
}
