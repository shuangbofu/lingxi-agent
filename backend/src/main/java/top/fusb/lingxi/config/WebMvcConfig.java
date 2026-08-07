package top.fusb.lingxi.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AgentRuntimeInterceptor agentRuntimeInterceptor;

    /**
     * 注册 API 登录态拦截器。
     *
     * @param registry MVC 拦截器注册器
     * @return 无返回值
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/**",
                        "/api/public/**",
                        "/api/definition-assets/scenarios/**",
                        "/api/agent-runtime/**"
                );
        registry.addInterceptor(agentRuntimeInterceptor)
                .addPathPatterns("/api/agent-runtime/**");
    }
}
