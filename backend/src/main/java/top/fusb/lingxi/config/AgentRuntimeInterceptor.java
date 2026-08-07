package top.fusb.lingxi.config;

import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.runtime.capability.AgentRuntimeAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class AgentRuntimeInterceptor implements HandlerInterceptor {

    private final AgentRuntimeAccessService agentRuntimeAccessService;

    /**
     * 校验内部能力运行时请求携带的任务令牌。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param handler Spring MVC 处理器
     * @return 校验通过返回 true
     * @throws BizException 令牌无效、任务参数非法或跨任务访问时抛出
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Long requestedTaskId = taskId(request.getParameter("taskId"));
        AgentRuntimeAccessService.RuntimeGrant grant = agentRuntimeAccessService.require(
                request.getHeader(AgentRuntimeAccessService.TOKEN_HEADER), requestedTaskId);
        agentRuntimeAccessService.requirePath(grant, request.getRequestURI());
        request.setAttribute(AgentRuntimeAccessService.TASK_ID_ATTRIBUTE, grant.taskId());
        return true;
    }

    private Long taskId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "任务 ID 格式错误");
        }
    }
}
