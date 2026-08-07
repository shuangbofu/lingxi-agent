package top.fusb.lingxi.runtime.langchain.util;

public final class LangChainErrorMessageKit {

    private LangChainErrorMessageKit() {
    }

    /**
     * 将运行时内部异常转换为面向用户的消息，同时隐藏底层框架名称和异常类型。
     *
     * @param error 运行时内部抛出的异常
     * @return 不包含底层框架名称的用户提示
     */
    public static String userMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "未知错误";
        }
        return error.getMessage()
                .replaceAll("(?i)langchain4j\\s*", "模型服务")
                .replaceAll("(?i)langchain\\s+agent\\s*", "任务")
                .replaceAll("(?i)langchain\\s*", "模型运行时");
    }
}
