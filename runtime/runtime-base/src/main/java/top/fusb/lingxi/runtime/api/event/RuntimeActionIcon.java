package top.fusb.lingxi.runtime.api.event;

/**
 * Runtime 动作的通用展示图标语义，前端据此选择一致的图标组件。
 */
public enum RuntimeActionIcon {
    WRENCH,
    TERMINAL,
    BOOK_OPEN,
    FILE_TEXT,
    FILE_PLUS,
    FILE_MAGNIFYING_GLASS,
    MAGNIFYING_GLASS,
    BRACKETS_CURLY,
    CODE,
    GRAPH,
    GIT_DIFF,
    TREE_STRUCTURE,
    PLUGS_CONNECTED,
    QUESTION,
    LIST_BULLETS,
    CHECK_CIRCLE,
    CALENDAR,
    CLOCK,
    DATABASE,
    FOLDER,
    GIT_BRANCH,
    GIT_COMMIT,
    SHIELD_CHECK,
    ARROW_COUNTER_CLOCKWISE,
    PLAY_CIRCLE,
    VIDEO_CAMERA,
    IMAGE_SQUARE,
    EYE,
    HISTORY,
    BROWSER,
    HEAD_CIRCUIT,
    WARNING_CIRCLE,
    X_CIRCLE;

    /**
     * 将能力扩展协议中的图标标识转换为运行时图标语义。
     *
     * @param value lingxi.json 中的图标标识，允许为空
     * @return 对应的运行时图标语义；输入为空时返回 null
     * @throws IllegalArgumentException 图标标识不在协议支持范围内时抛出
     */
    public static RuntimeActionIcon fromExternalValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return RuntimeActionIcon.valueOf(value.trim());
    }
}
