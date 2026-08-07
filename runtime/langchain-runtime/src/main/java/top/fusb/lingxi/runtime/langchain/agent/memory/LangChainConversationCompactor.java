package top.fusb.lingxi.runtime.langchain.agent.memory;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

@FunctionalInterface
public interface LangChainConversationCompactor {

    /**
     * 将已完成的旧调查过程合并为可继续执行的结构化状态。
     *
     * @param originalTask 原始任务消息，包含场景目标、边界和交付要求
     * @param previousSummary 上一次压缩状态，首次压缩时为空
     * @param messages 本次需要并入状态的完整消息块
     * @return 可替代旧消息块的结构化调查状态
     * @throws RuntimeException 模型压缩失败或返回空结果时抛出
     */
    String compact(ChatMessage originalTask, String previousSummary, List<ChatMessage> messages);
}
