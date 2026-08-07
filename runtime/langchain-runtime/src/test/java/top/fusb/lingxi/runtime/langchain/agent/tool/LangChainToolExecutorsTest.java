package top.fusb.lingxi.runtime.langchain.agent.tool;

import top.fusb.lingxi.runtime.api.execution.RuntimeToolExecutionMode;
import top.fusb.lingxi.runtime.langchain.agent.LangChainExecutionContext;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LangChainToolExecutorsTest {

    @Test
    void appliesReadOnlyAndSerialPoliciesToBuiltinExecutors() throws Exception {
        LangChainExecutionContext context = new LangChainExecutionContext(
                "tool-executors", Path.of("."), event -> { });
        PolicyTools tools = new PolicyTools();
        Map<ToolSpecification, ToolExecutor> executors = LangChainToolExecutors.create(List.of(tools), context);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            try (var ignored = context.acquireToolExecution(RuntimeToolExecutionMode.READ_ONLY)) {
                threads.submit(() -> execute(executors, "read_file"));
                assertThat(tools.readInvoked.await(1, TimeUnit.SECONDS)).isTrue();
                threads.submit(() -> execute(executors, "query_evidence"));
                assertThat(tools.queryEvidenceInvoked.await(1, TimeUnit.SECONDS)).isTrue();

                threads.submit(() -> execute(executors, "write_workspace_file"));
                assertThat(tools.writeInvoked.await(150, TimeUnit.MILLISECONDS)).isFalse();
            }
            assertThat(tools.writeInvoked.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void cancellationInterruptsActiveToolAndReturnsAbortedResult() throws Exception {
        LangChainExecutionContext context = new LangChainExecutionContext(
                "tool-cancellation", Path.of("."), event -> { });
        PolicyTools tools = new PolicyTools();
        Map<ToolSpecification, ToolExecutor> executors = LangChainToolExecutors.create(List.of(tools), context);
        ExecutorService threads = Executors.newSingleThreadExecutor();
        try {
            var result = threads.submit(() -> executor(executors, "blocking_tool").executeWithContext(
                    request("blocking_tool"), null));
            assertThat(tools.blockingInvoked.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(context.cancel()).isTrue();

            var aborted = result.get(1, TimeUnit.SECONDS);
            assertThat(aborted.isError()).isTrue();
            assertThat(aborted.resultText()).isEqualTo(LangChainToolExecutors.ABORTED_RESULT);
            assertThat(tools.blockingInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            threads.shutdownNow();
        }
    }

    private String execute(Map<ToolSpecification, ToolExecutor> executors, String toolName) {
        return executor(executors, toolName).execute(request(toolName), null);
    }

    private ToolExecutor executor(Map<ToolSpecification, ToolExecutor> executors, String toolName) {
        return executors.entrySet().stream()
                .filter(entry -> entry.getKey().name().equals(toolName))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
    }

    private ToolExecutionRequest request(String toolName) {
        return ToolExecutionRequest.builder()
                .id("call-" + toolName)
                .name(toolName)
                .arguments("{}")
                .build();
    }

    static final class PolicyTools {

        private final CountDownLatch readInvoked = new CountDownLatch(1);
        private final CountDownLatch queryEvidenceInvoked = new CountDownLatch(1);
        private final CountDownLatch writeInvoked = new CountDownLatch(1);
        private final CountDownLatch blockingInvoked = new CountDownLatch(1);
        private final CountDownLatch blockingInterrupted = new CountDownLatch(1);

        @Tool(name = "read_file")
        public String readFile() {
            readInvoked.countDown();
            return "read";
        }

        @Tool(name = "query_evidence")
        public String queryEvidence() {
            queryEvidenceInvoked.countDown();
            return "query";
        }

        @Tool(name = "write_workspace_file")
        public String writeWorkspaceFile() {
            writeInvoked.countDown();
            return "write";
        }

        @Tool(name = "blocking_tool")
        public String blockingTool() {
            blockingInvoked.countDown();
            try {
                new CountDownLatch(1).await();
                return "completed";
            } catch (InterruptedException exception) {
                blockingInterrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", exception);
            }
        }
    }
}
