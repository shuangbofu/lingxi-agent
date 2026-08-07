package top.fusb.lingxi.enums;

public enum TaskInteractionInputType {
    TEXT,
    TEXTAREA,
    YES_NO,
    DATE,
    DATETIME,
    SELECT,
    SINGLE_CHOICE,
    MULTI_CHOICE,
    CONFIRM,
    FORM;

    /**
     * 将外部输入类型转换为枚举，默认使用文本输入。
     *
     * @param value 输入类型编码
     * @return 输入类型枚举
     */
    public static TaskInteractionInputType parse(String value) {
        if (value == null || value.isBlank()) {
            return TEXT;
        }
        String normalized = value.trim().replace("-", "_").toUpperCase();
        if ("BOOLEAN".equals(normalized) || "BOOL".equals(normalized) || "YESNO".equals(normalized)) {
            return YES_NO;
        }
        if ("DATE_TIME".equals(normalized) || "TIME".equals(normalized)) {
            return DATETIME;
        }
        if ("DROPDOWN".equals(normalized) || "COMBOBOX".equals(normalized)) {
            return SELECT;
        }
        if ("SINGLE".equals(normalized) || "RADIO".equals(normalized)) {
            return SINGLE_CHOICE;
        }
        if ("MULTI".equals(normalized) || "MULTIPLE".equals(normalized) || "CHECKBOX".equals(normalized)) {
            return MULTI_CHOICE;
        }
        for (TaskInteractionInputType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return TEXT;
    }
}
