package top.fusb.lingxi.runtime.langchain.agent;

public final class LangChainExecutionLimitException extends IllegalStateException {

    public LangChainExecutionLimitException(String message) {
        super(message);
    }
}
