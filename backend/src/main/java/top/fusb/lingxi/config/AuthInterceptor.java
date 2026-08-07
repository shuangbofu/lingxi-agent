package top.fusb.lingxi.config;

import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService authService;

    /**
     * 校验 API 登录态和方法权限。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param handler Spring MVC 处理器
     * @return 校验通过返回 true
     * @throws BizException 未登录或权限不足时抛出
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        UserEntity user = authService.requireUser(request.getSession(false));
        RequirePermission permission = method.getMethodAnnotation(RequirePermission.class);
        if (permission == null) {
            permission = method.getBeanType().getAnnotation(RequirePermission.class);
        }
        if (permission != null && !authService.hasPermission(user, permission.value())) {
            throw new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.FORBIDDEN, "没有操作权限");
        }
        return true;
    }
}
