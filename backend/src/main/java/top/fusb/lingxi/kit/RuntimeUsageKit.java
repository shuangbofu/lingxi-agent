package top.fusb.lingxi.kit;

import top.fusb.lingxi.runtime.api.model.RuntimeUsage;

public final class RuntimeUsageKit {

    private RuntimeUsageKit() {
    }

    /**
     * 汇总两个独立模型阶段的 Token 用量，最后一次请求字段取后一个阶段。
     *
     * @param first 前一个阶段的累计用量
     * @param second 后一个阶段的累计用量
     * @return 两个阶段的汇总用量；都为空时返回 null
     */
    public static RuntimeUsage add(RuntimeUsage first, RuntimeUsage second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return new RuntimeUsage(
                second.eventTimestamp() == null ? first.eventTimestamp() : second.eventTimestamp(),
                safe(first.requestCount()) + safe(second.requestCount()),
                safe(first.inputTokens()) + safe(second.inputTokens()),
                safe(first.cachedInputTokens()) + safe(second.cachedInputTokens()),
                safe(first.cacheCreationInputTokens()) + safe(second.cacheCreationInputTokens()),
                safe(first.outputTokens()) + safe(second.outputTokens()),
                safe(first.reasoningOutputTokens()) + safe(second.reasoningOutputTokens()),
                safe(first.totalTokens()) + safe(second.totalTokens()),
                second.lastInputTokens(),
                second.lastCachedInputTokens(),
                second.lastCacheCreationInputTokens(),
                second.lastOutputTokens(),
                second.lastReasoningOutputTokens(),
                second.lastTotalTokens(),
                second.modelContextWindow()
        );
    }

    private static long safe(Long value) {
        return value == null ? 0L : value;
    }
}
