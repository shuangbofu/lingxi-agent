package top.fusb.lingxi.enums;

import java.util.Locale;

public enum TaskEventType {
    SYSTEM,
    THINKING,
    COMMAND,
    INTERACTION,
    AGENT_MESSAGE,
    METRIC,
    ERROR;

    /**
     * 将持久化事件类型转换为平台事件类型，未知操作统一按命令展示。
     *
     * @param value 持久化的事件类型
     * @return 平台事件类型
     */
    public static TaskEventType parse(String value) {
        if (value == null || value.isBlank()) {
            return SYSTEM;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return COMMAND;
        }
    }
}
