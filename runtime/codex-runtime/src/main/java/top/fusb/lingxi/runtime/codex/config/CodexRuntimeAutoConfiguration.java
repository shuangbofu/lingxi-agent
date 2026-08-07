package top.fusb.lingxi.runtime.codex.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.runtime.api.AgentRuntime;
import top.fusb.lingxi.runtime.codex.cli.CodexEventParser;
import top.fusb.lingxi.runtime.codex.core.CodexRuntime;
import top.fusb.lingxi.runtime.codex.core.CodexRuntimeDelegate;
import top.fusb.lingxi.runtime.codex.core.DefaultCodexRuntimeDelegate;
import top.fusb.lingxi.runtime.codex.execution.CodexExecutor;
import top.fusb.lingxi.runtime.codex.home.CodexHomeService;
import top.fusb.lingxi.runtime.codex.maintenance.CodexCliManager;
import top.fusb.lingxi.runtime.codex.session.CodexSessionEventStream;
import top.fusb.lingxi.runtime.codex.usage.CodexUsageSessionService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(CodexRuntimeProperties.class)
public class CodexRuntimeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CodexEventParser codexEventParser(ObjectMapper objectMapper) {
        return new CodexEventParser(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public CodexSessionEventStream codexSessionEventStream(ObjectMapper objectMapper) {
        return new CodexSessionEventStream(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public CodexCliManager codexCliManager(CodexRuntimeProperties properties, ObjectMapper objectMapper) {
        return new CodexCliManager(properties, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public CodexHomeService codexHomeService(CodexRuntimeProperties properties) {
        return new CodexHomeService(properties.getLocale());
    }

    @Bean
    @ConditionalOnMissingBean
    public CodexUsageSessionService codexUsageSessionService(CodexHomeService homeService,
                                                             CodexEventParser eventParser) {
        return new CodexUsageSessionService(homeService, eventParser);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public CodexExecutor codexExecutor(CodexRuntimeProperties properties,
                                       CodexCliManager cliManager,
                                       CodexHomeService homeService,
                                       CodexUsageSessionService usageSessionService,
                                       ObjectMapper objectMapper,
                                       CodexEventParser eventParser,
                                       CodexSessionEventStream sessionEventStream) {
        return new CodexExecutor(properties, cliManager, homeService, usageSessionService,
                objectMapper, eventParser, sessionEventStream);
    }

    @Bean
    @ConditionalOnMissingBean(CodexRuntimeDelegate.class)
    public CodexRuntimeDelegate defaultCodexRuntimeDelegate(CodexExecutor executor,
                                                            CodexHomeService homeService,
                                                            CodexUsageSessionService usageSessionService,
                                                            CodexRuntimeProperties properties) {
        return new DefaultCodexRuntimeDelegate(executor, homeService, usageSessionService, properties);
    }

    @Bean("codexAgentRuntime")
    @ConditionalOnBean(CodexRuntimeDelegate.class)
    @ConditionalOnMissingBean(name = "codexAgentRuntime")
    public AgentRuntime codexAgentRuntime(CodexRuntimeDelegate delegate, CodexCliManager cliManager) {
        return new CodexRuntime(delegate, cliManager);
    }
}
