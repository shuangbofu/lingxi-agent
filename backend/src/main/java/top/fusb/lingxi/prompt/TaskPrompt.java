package top.fusb.lingxi.prompt;

public record TaskPrompt(String instructions, String userMessage, String finalResponseInstructions) {

    /**
     * 按原有单 Prompt 顺序组合任务内容，供展示和不支持消息角色的 Runtime 使用。
     *
     * @return 稳定指令与本轮输入组成的完整任务 Prompt
     */
    public String combined() {
        if (instructions == null || instructions.isBlank()) {
            return userMessage == null ? "" : userMessage.strip();
        }
        if (userMessage == null || userMessage.isBlank()) {
            return instructions.strip();
        }
        return instructions.strip() + System.lineSeparator() + System.lineSeparator() + userMessage.strip();
    }
}
