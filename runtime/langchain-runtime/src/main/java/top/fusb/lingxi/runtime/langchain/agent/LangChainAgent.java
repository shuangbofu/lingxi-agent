package top.fusb.lingxi.runtime.langchain.agent;

import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

public interface LangChainAgent {

    TokenStream chat(@UserMessage String prompt);
}
