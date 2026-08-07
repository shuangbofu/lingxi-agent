package top.fusb.lingxi.runtime.langchain.config;

import top.fusb.lingxi.runtime.api.AgentRuntime;
import top.fusb.lingxi.runtime.langchain.agent.DefaultLangChainRuntimeDelegate;
import top.fusb.lingxi.runtime.langchain.core.LangChainRuntime;
import top.fusb.lingxi.runtime.langchain.core.LangChainRuntimeDelegate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Set;

@AutoConfiguration
@EnableConfigurationProperties(LangChainRuntimeProperties.class)
public class LangChainRuntimeAutoConfiguration {

    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production");

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(LangChainRuntimeDelegate.class)
    public LangChainRuntimeDelegate defaultLangChainRuntimeDelegate(LangChainRuntimeProperties properties,
                                                                    Environment environment) {
        return new DefaultLangChainRuntimeDelegate(properties, requestInputSnapshotsEnabled(properties, environment));
    }

    @Bean("langChainAgentRuntime")
    @ConditionalOnBean(LangChainRuntimeDelegate.class)
    @ConditionalOnMissingBean(name = "langChainAgentRuntime")
    public AgentRuntime langChainAgentRuntime(LangChainRuntimeDelegate delegate) {
        return new LangChainRuntime(delegate);
    }

    /**
     * 判断是否允许写入包含完整模型输入的本地调试快照。
     *
     * @param properties LangChain Runtime 配置
     * @param environment 当前 Spring 运行环境
     * @return 仅显式开启且不是生产 profile 时返回 true
     */
    boolean requestInputSnapshotsEnabled(LangChainRuntimeProperties properties, Environment environment) {
        if (!properties.getDebug().isRequestInputSnapshotsEnabled()) {
            return false;
        }
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length == 0) {
            profiles = environment.getDefaultProfiles();
        }
        return Arrays.stream(profiles)
                .map(String::toLowerCase)
                .noneMatch(PRODUCTION_PROFILES::contains);
    }
}
