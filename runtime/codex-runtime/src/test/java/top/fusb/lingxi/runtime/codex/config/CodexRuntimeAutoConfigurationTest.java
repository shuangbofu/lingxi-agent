package top.fusb.lingxi.runtime.codex.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.runtime.api.AgentRuntime;
import top.fusb.lingxi.runtime.codex.cli.CodexEventParser;
import top.fusb.lingxi.runtime.codex.maintenance.CodexCliManager;
import top.fusb.lingxi.runtime.codex.session.CodexSessionEventStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CodexRuntimeAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CodexRuntimeAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void registersCodexRuntimeWhenDelegateIsAvailable() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CodexEventParser.class);
            assertThat(context).hasSingleBean(CodexSessionEventStream.class);
            assertThat(context).hasSingleBean(CodexCliManager.class);
            assertThat(context).hasBean("codexAgentRuntime");
            AgentRuntime runtime = context.getBean("codexAgentRuntime", AgentRuntime.class);
            assertThat(runtime.code()).isEqualTo("codex");
            assertThat(runtime.descriptor().maintenanceSupported()).isTrue();
            assertThat(runtime.descriptor().mcpSupported()).isTrue();
            assertThat(runtime.maintenance()).isPresent();
        });
    }

    @Test
    void bindsRuntimeOverridesWithoutDefaultsPropertySource() {
        contextRunner.withPropertyValues("lingxi.codex.executable=/opt/codex")
                .run(context -> assertThat(context.getBean(CodexRuntimeProperties.class).getExecutable())
                        .isEqualTo("/opt/codex"));
    }
}
