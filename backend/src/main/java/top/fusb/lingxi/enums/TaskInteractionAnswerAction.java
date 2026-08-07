package top.fusb.lingxi.enums;

public enum TaskInteractionAnswerAction {
    SUBMIT,
    SKIP,
    UNKNOWN,
    CANCEL;

    /**
     * 将前端提交的交互动作转换为枚举，默认按正常提交处理。
     *
     * @param value 交互动作编码
     * @return 交互动作枚举
     */
    public static TaskInteractionAnswerAction parse(String value) {
        if (value == null || value.isBlank()) {
            return SUBMIT;
        }
        String normalized = value.trim().replace("-", "_").toUpperCase();
        for (TaskInteractionAnswerAction action : values()) {
            if (action.name().equals(normalized)) {
                return action;
            }
        }
        return SUBMIT;
    }
}
