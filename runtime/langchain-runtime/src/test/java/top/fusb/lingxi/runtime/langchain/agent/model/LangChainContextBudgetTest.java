package top.fusb.lingxi.runtime.langchain.agent.model;

import top.fusb.lingxi.runtime.langchain.agent.memory.LangChainTokenCountEstimator;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LangChainContextBudgetTest {

    @Test
    void usesTheSameTotalForMemoryAndTheActualModelRequest() {
        LangChainContextBudget budget = new LangChainContextBudget(
                new LangChainTokenCountEstimator("gpt-5", 256),
                10_000, 0.8D, "任务规则", "MCP 规则");
        ToolSpecification tool = ToolSpecification.builder()
                .name("read_file")
                .description("读取文件")
                .build();
        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from("平台规则\n任务规则\nMCP 规则"), UserMessage.from("检查实现"))
                .toolSpecifications(tool)
                .build();
        budget.reserveToolSpecifications(List.of(tool));

        LangChainContextBudget.Snapshot memory = budget.estimateMessages(request.messages());
        LangChainContextBudget.Snapshot modelRequest = budget.estimate(request);

        assertThat(memory.estimatedInputTokens()).isEqualTo(modelRequest.estimatedInputTokens());
        assertThat(memory.toolSchemaTokens()).isEqualTo(modelRequest.toolSchemaTokens()).isPositive();
        assertThat(memory.remainingTokens())
                .isEqualTo(memory.inputTokenBudget() - memory.estimatedInputTokens());
    }

    @Test
    void countsImageTokensExactlyOnceInTheExclusiveBreakdown() {
        LangChainContextBudget budget = new LangChainContextBudget(
                new LangChainTokenCountEstimator("gpt-5", 256),
                10_000, 0.8D, null, null);
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from(List.of(
                        TextContent.from("分析图片"),
                        ImageContent.from("aW1hZ2U=", "image/png"))))
                .build();

        LangChainContextBudget.Snapshot snapshot = budget.estimate(request);
        long classified = snapshot.systemInstructionTokens() + snapshot.taskInstructionTokens()
                + snapshot.mcpInstructionTokens() + snapshot.toolSchemaTokens()
                + snapshot.conversationTokens() + snapshot.toolResultTokens() + snapshot.imageTokens();

        assertThat(snapshot.imageTokens()).isEqualTo(256L);
        assertThat(classified).isEqualTo(snapshot.estimatedInputTokens());
    }

    @Test
    void keepsMessageOnlyTotalWhenNoToolSchemaIsConfigured() {
        LangChainTokenCountEstimator estimator = new LangChainTokenCountEstimator("gpt-5", 256);
        LangChainContextBudget budget = new LangChainContextBudget(estimator, 10_000, 0.8D, null, null);
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("普通对话")).build();

        LangChainContextBudget.Snapshot snapshot = budget.estimate(request);

        assertThat(snapshot.toolSchemaTokens()).isZero();
        assertThat(snapshot.estimatedInputTokens())
                .isEqualTo(estimator.estimateTokenCountInMessages(request.messages()));
    }
}
