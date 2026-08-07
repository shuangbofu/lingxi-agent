package top.fusb.lingxi.runtime.langchain.config;

import top.fusb.lingxi.runtime.api.AgentRuntime;
import top.fusb.lingxi.runtime.api.event.RuntimeEventListener;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionRequest;
import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionResult;
import top.fusb.lingxi.runtime.api.model.RuntimeSessionRef;
import top.fusb.lingxi.runtime.api.model.RuntimeUsage;
import top.fusb.lingxi.runtime.api.model.RuntimeUsageQuery;
import top.fusb.lingxi.runtime.langchain.core.LangChainRuntimeDelegate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LangChainRuntimeAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LangChainRuntimeAutoConfiguration.class));
    private final LangChainRuntimeAutoConfiguration configuration = new LangChainRuntimeAutoConfiguration();

    @Test
    void registersDefaultRuntime() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("defaultLangChainRuntimeDelegate");
            assertThat(context).hasBean("langChainAgentRuntime");
            assertThat(context.getBean("langChainAgentRuntime", AgentRuntime.class).code())
                    .isEqualTo("langchain");
        });
    }

    @Test
    void bindsRuntimeOverridesWithoutDefaultsPropertySource() {
        contextRunner.withPropertyValues("lingxi.langchain.tool-concurrency=3")
                .run(context -> assertThat(context.getBean(LangChainRuntimeProperties.class).getToolConcurrency())
                        .isEqualTo(3));
    }

    @Test
    void usesCustomDelegateWhenAvailable() {
        contextRunner.withBean(LangChainRuntimeDelegate.class, StubDelegate::new)
                .run(context -> {
                    assertThat(context).doesNotHaveBean("defaultLangChainRuntimeDelegate");
                    assertThat(context).hasBean("langChainAgentRuntime");
                    assertThat(context.getBean("langChainAgentRuntime", AgentRuntime.class).code())
                            .isEqualTo("langchain");
                });
    }

    @Test
    void disablesRequestInputSnapshotsByDefault() {
        assertThat(configuration.requestInputSnapshotsEnabled(
                new LangChainRuntimeProperties(), new MockEnvironment())).isFalse();
    }

    @Test
    void enablesRequestInputSnapshotsOnlyForNonProductionDebugging() {
        LangChainRuntimeProperties properties = new LangChainRuntimeProperties();
        properties.getDebug().setRequestInputSnapshotsEnabled(true);
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("production");

        assertThat(configuration.requestInputSnapshotsEnabled(properties, local)).isTrue();
        assertThat(configuration.requestInputSnapshotsEnabled(properties, prod)).isFalse();
        assertThat(configuration.requestInputSnapshotsEnabled(properties, production)).isFalse();
    }

    private static class StubDelegate implements LangChainRuntimeDelegate {

        @Override
        public RuntimeExecutionResult execute(RuntimeExecutionRequest request, RuntimeEventListener listener) {
            return null;
        }

        @Override
        public boolean cancel(String executionId) {
            return false;
        }

        @Override
        public long defaultTimeoutSeconds() {
            return 900L;
        }

        @Override
        public Optional<RuntimeSessionRef> latestSession(String conversationId) {
            return Optional.empty();
        }

        @Override
        public Optional<RuntimeUsage> readUsage(RuntimeUsageQuery query) {
            return Optional.empty();
        }
    }
}
