package top.fusb.lingxi.runtime.langchain.agent;

import top.fusb.lingxi.runtime.api.event.RuntimeEvent;
import top.fusb.lingxi.runtime.api.event.RuntimeEventListener;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionEnvironment;
import top.fusb.lingxi.runtime.api.execution.RuntimeToolExecutionMode;
import top.fusb.lingxi.runtime.api.model.RuntimeMessageDelta;
import top.fusb.lingxi.runtime.api.model.RuntimeMessageDeltaType;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LangChainExecutionContextTest {

    @Test
    void forwardsMessageDeltaUntilExecutionIsCancelled() {
        List<RuntimeMessageDelta> deltas = new ArrayList<>();
        LangChainExecutionContext context = new LangChainExecutionContext(
                "execution-1", Path.of("."), new RuntimeEventListener() {
                    @Override
                    public void onEvent(RuntimeEvent event) {
                    }

                    @Override
                    public void onMessageDelta(RuntimeMessageDelta delta) {
                        deltas.add(delta);
                    }
                });

        context.emitMessageDelta(new RuntimeMessageDelta("round-1", "第一段"));
        context.emitMessageDelta(new RuntimeMessageDelta(
                "reasoning-round-1", "推理片段", RuntimeMessageDeltaType.REASONING));
        context.cancel();
        context.emitMessageDelta(new RuntimeMessageDelta("round-1", "不应发送"));

        assertThat(deltas).containsExactly(
                new RuntimeMessageDelta("round-1", "第一段"),
                new RuntimeMessageDelta("reasoning-round-1", "推理片段", RuntimeMessageDeltaType.REASONING));
    }

    @Test
    void reportsConfiguredModelContextWindowWithUsage() {
        LangChainExecutionContext context = new LangChainExecutionContext(
                "execution-2", Path.of("."), new RuntimeEventListener() {
                    @Override
                    public void onEvent(RuntimeEvent event) {
                    }

                    @Override
                    public void onMessageDelta(RuntimeMessageDelta delta) {
                    }
                }, 200_000L);

        context.beginModelRequest();
        context.addUsage(new TokenUsage(120, 30, 150));

        assertThat(context.usage()).isNotNull();
        assertThat(context.usage().modelContextWindow()).isEqualTo(200_000L);
        assertThat(context.usage().requestCount()).isEqualTo(1L);
    }

    @Test
    void countsFailedRequestsSeparatelyFromTokenUsageAndPreservesProviderDetails() {
        List<top.fusb.lingxi.runtime.api.model.RuntimeUsage> updates = new ArrayList<>();
        LangChainExecutionContext context = new LangChainExecutionContext(
                "execution-usage", Path.of("."), new RuntimeEventListener() {
                    @Override
                    public void onEvent(RuntimeEvent event) {
                    }

                    @Override
                    public void onUsage(top.fusb.lingxi.runtime.api.model.RuntimeUsage usage) {
                        updates.add(usage);
                    }
                }, 200_000L, 10, 2, 10_000L);
        OpenAiTokenUsage usage = OpenAiTokenUsage.builder()
                .inputTokenCount(120)
                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(80).build())
                .outputTokenCount(30)
                .outputTokensDetails(OpenAiTokenUsage.OutputTokensDetails.builder().reasoningTokens(20).build())
                .totalTokenCount(150)
                .build();

        context.beginModelRequest();
        context.addUsage(usage);
        context.beginModelRequest();

        assertThat(context.usage().requestCount()).isEqualTo(2L);
        assertThat(context.usage().inputTokens()).isEqualTo(120L);
        assertThat(context.usage().cachedInputTokens()).isEqualTo(80L);
        assertThat(context.usage().reasoningOutputTokens()).isEqualTo(20L);
        assertThat(context.usage().lastCachedInputTokens()).isEqualTo(80L);
        assertThat(updates).hasSize(3);
    }

    @Test
    void tracksModelRequestsAndEnforcesResourceBudgets() {
        LangChainExecutionContext context = new LangChainExecutionContext(
                "execution-budget", Path.of("."), new RuntimeEventListener() {
                    @Override
                    public void onEvent(RuntimeEvent event) {
                    }
                }, null, 3, 1, 100L);

        context.beginModelRequest();
        context.beginModelRequest();
        context.beginCapabilityCall("demo.run", List.of("--id", "1"));

        assertThat(context.usage().requestCount()).isEqualTo(2L);
        context.addUsage(new TokenUsage(60, 60, 120));
        org.assertj.core.api.Assertions.assertThatThrownBy(context::beginModelRequest)
                .isInstanceOf(LangChainExecutionLimitException.class)
                .hasMessageContaining("Token 用量");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> context.beginCapabilityCall("demo.run", List.of("--id", "1")))
                .isInstanceOf(LangChainExecutionLimitException.class)
                .hasMessageContaining("重复调用上限");
        context.beginToolCall("read_file");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> context.beginToolCall("read_file"))
                .isInstanceOf(LangChainExecutionLimitException.class)
                .hasMessageContaining("工具调用次数");
    }

    @Test
    void keepsEvidenceReadsOutsideTheGeneralToolBudget() {
        LangChainExecutionContext context = new LangChainExecutionContext(
                "execution-evidence-budget", Path.of("."), event -> { },
                null, 1, 2, Long.MAX_VALUE);

        context.beginEvidenceRead();
        context.beginToolCall("read_file");

        org.assertj.core.api.Assertions.assertThatThrownBy(context::beginEvidenceRead)
                .isInstanceOf(LangChainExecutionLimitException.class)
                .hasMessageContaining("Evidence 读取次数", "1");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> context.beginToolCall("grep"))
                .isInstanceOf(LangChainExecutionLimitException.class)
                .hasMessageContaining("工具调用次数", "1");
    }

    @Test
    void doesNotLimitAccumulatedModelTokensWhenBudgetIsDisabled() {
        LangChainExecutionContext context = new LangChainExecutionContext(
                "execution-unlimited", Path.of("."), new RuntimeEventListener() {
                    @Override
                    public void onEvent(RuntimeEvent event) {
                    }
                }, RuntimeExecutionEnvironment.empty(),
                null, 10, 2, 0L, 300_000L);

        context.beginModelRequest();
        context.addUsage(new TokenUsage(5_100_000, 100_000, 5_200_000));
        context.beginModelRequest();

        assertThat(context.usage().totalTokens()).isEqualTo(5_200_000L);
        assertThat(context.usage().requestCount()).isEqualTo(2L);
        assertThat(context.finalizationRequired()).isFalse();
    }

    @Test
    void timeFinalizationStopsNewToolCallsWithoutCancellingExecution() {
        LangChainExecutionContext context = new LangChainExecutionContext(
                "execution-time-finalization", Path.of("."), event -> { });

        assertThat(context.enterTimeFinalization()).isTrue();
        assertThat(context.enterTimeFinalization()).isFalse();
        assertThat(context.finalizationRequired()).isTrue();
        assertThat(context.isCancelled()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> context.beginToolCall("read_file"))
                .isInstanceOf(LangChainExecutionLimitException.class)
                .hasMessageContaining("收尾阶段");
    }

    @Test
    void allowsReadOnlyToolsToOverlapAndSerialToolsToWait() throws Exception {
        LangChainExecutionContext context = new LangChainExecutionContext(
                "execution-tool-policy", Path.of("."), event -> { });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch serialStarted = new CountDownLatch(1);
        CountDownLatch serialEntered = new CountDownLatch(1);
        try {
            try (var firstRead = context.acquireToolExecution(RuntimeToolExecutionMode.READ_ONLY)) {
                var parallelRead = executor.submit(() -> {
                    try (var ignored = context.acquireToolExecution(RuntimeToolExecutionMode.READ_ONLY)) {
                        return true;
                    }
                });
                assertThat(parallelRead.get(1, TimeUnit.SECONDS)).isTrue();

                var serial = executor.submit(() -> {
                    serialStarted.countDown();
                    try (var ignored = context.acquireToolExecution(RuntimeToolExecutionMode.SERIAL)) {
                        serialEntered.countDown();
                    }
                    return true;
                });
                assertThat(serialStarted.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(serialEntered.await(150, TimeUnit.MILLISECONDS)).isFalse();
                assertThat(serial.isDone()).isFalse();
            }
            assertThat(serialEntered.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }
}
