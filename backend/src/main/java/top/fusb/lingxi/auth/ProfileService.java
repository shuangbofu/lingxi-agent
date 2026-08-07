package top.fusb.lingxi.auth;

import top.fusb.lingxi.dto.DashboardTokenUsageResponse;
import top.fusb.lingxi.dto.ProfileResponse;
import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.service.DashboardService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProfileService {

    private final AuthService authService;
    private final DashboardService dashboardService;
    private final UserUsageQuotaService userUsageQuotaService;

    /**
     * 查询当前用户个人资料和用量状态。
     *
     * @param session 当前 HTTP 会话
     * @return 当前用户个人资料
     */
    public ProfileResponse detail(HttpSession session) {
        UserEntity user = authService.requireUser(session);
        ProfileResponse response = new ProfileResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setDisplayName(user.getDisplayName());
        response.setAvatarUrl(user.getAvatarUrl());
        response.setRole(user.getRole().name());
        response.setRoleName(user.getRole().getDescription());
        response.setUsageQuota(userUsageQuotaService.current(user));
        return response;
    }

    /**
     * 查询当前用户指定时间范围内的真实用量聚合。
     *
     * @param createdStart 统计开始时间，ISO 本地时间字符串
     * @param createdEnd 统计结束时间，ISO 本地时间字符串
     * @param session 当前 HTTP 会话
     * @return 当前用户在指定时间范围内的用量聚合
     */
    public DashboardTokenUsageResponse usage(String createdStart, String createdEnd, HttpSession session) {
        UserEntity user = authService.requireUser(session);
        return dashboardService.tokenUsage(createdStart, createdEnd, user.getId(), null, null, null, "day");
    }
}
