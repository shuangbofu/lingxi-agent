package top.fusb.lingxi.runtime.langchain.capability;

import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
public interface LangChainCapabilityOutputHandler {

    /**
     * 将能力命令声明的资源转换为 LangChain Runtime 可使用的运行时资源。
     *
     * @param output 能力 manifest 声明并由命令返回的通用资源
     * @return 返回给 Agent 的运行时资源描述
     * @throws Exception 资源校验或运行时准备失败时抛出
     */
    JsonNode handle(LangChainCapabilityRegistry.CapabilityOutput output) throws Exception;
}
