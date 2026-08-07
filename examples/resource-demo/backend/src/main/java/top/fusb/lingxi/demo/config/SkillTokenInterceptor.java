package top.fusb.lingxi.demo.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import top.fusb.lingxi.demo.web.BusinessException;
import top.fusb.lingxi.demo.web.ErrorCode;

@Component
@RequiredArgsConstructor
public class SkillTokenInterceptor implements HandlerInterceptor {

    private final DemoProperties properties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String expected = properties.getAccessToken();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        String token = request.getParameter("token");
        String authorization = request.getHeader("Authorization");
        if ((token == null || token.isBlank()) && authorization != null && authorization.startsWith("Bearer ")) {
            token = authorization.substring(7);
        }
        if (!expected.equals(token)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "访问令牌无效");
        }
        return true;
    }
}
