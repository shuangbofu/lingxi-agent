package top.fusb.lingxi.runtime.langchain.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LangChainRuntimeTest {

    @Test
    void hidesRuntimeImplementationNameFromExecutionErrors() {
        LangChainRuntimeDelegate delegate = mock(LangChainRuntimeDelegate.class);
        when(delegate.execute(null, null))
                .thenThrow(new IllegalStateException("LangChain Agent 执行失败：LangChain4j 请求异常"));
        LangChainRuntime runtime = new LangChainRuntime(delegate);

        assertThatThrownBy(() -> runtime.execute(null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("任务执行失败：模型服务请求异常")
                .hasMessageNotContaining("LangChain");
    }
}
