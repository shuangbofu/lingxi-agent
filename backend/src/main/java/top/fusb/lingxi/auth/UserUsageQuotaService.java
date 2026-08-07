package top.fusb.lingxi.auth;

import top.fusb.lingxi.dto.UserUsageQuotaPeriodResponse;
import top.fusb.lingxi.dto.UserUsageQuotaResponse;
import top.fusb.lingxi.runtime.config.RuntimeConfigService;
import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.runtime.config.RuntimeConfigEntity;
import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.repository.AgentTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;

@Service
@RequiredArgsConstructor
public class UserUsageQuotaService {

    private final AgentTaskRepository agentTaskRepository;
    private final RuntimeConfigService runtimeConfigService;

    /**
     * 查询用户当前日、周、月的 Token 配额及使用进度，用户未配置的档位继承全局配额。
     *
     * @param user 用户实体
     * @return 当前三个配额周期的状态
     */
    @Transactional(readOnly = true)
    public UserUsageQuotaResponse current(UserEntity user) {
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate monthStart = today.withDayOfMonth(1);
        RuntimeConfigEntity global = runtimeConfigService.getEntity();

        UserUsageQuotaResponse response = new UserUsageQuotaResponse();
        response.setDaily(period(user, today.atStartOfDay(), today.plusDays(1).atStartOfDay(),
                user == null ? null : user.getDailyTokenLimit(), global.getGlobalDailyTokenLimit()));
        response.setWeekly(period(user, weekStart.atStartOfDay(), weekStart.plusWeeks(1).atStartOfDay(),
                user == null ? null : user.getWeeklyTokenLimit(), global.getGlobalWeeklyTokenLimit()));
        response.setMonthly(period(user, monthStart.atStartOfDay(), monthStart.plusMonths(1).atStartOfDay(),
                user == null ? null : user.getMonthlyTokenLimit(), global.getGlobalMonthlyTokenLimit()));
        return response;
    }

    /**
     * 校验用户的日、周、月用量是否仍允许发起任务。
     *
     * @param user 任务所属用户
     * @return 无返回值
     * @throws BizException 任一配额周期已达到上限时抛出
     */
    @Transactional(readOnly = true)
    public void requireAvailable(UserEntity user) {
        if (user == null) {
            return;
        }
        UserUsageQuotaResponse quota = current(user);
        if (quota.getDaily().isExceeded()) {
            throw limitExceeded("今日 Token 用量已达到上限");
        }
        if (quota.getWeekly().isExceeded()) {
            throw limitExceeded("本周 Token 用量已达到上限");
        }
        if (quota.getMonthly().isExceeded()) {
            throw limitExceeded("本月 Token 用量已达到上限");
        }
    }

    /**
     * 判断任务所属用户是否已达到任一有效配额。
     *
     * @param task 任务实体
     * @return 任一日、周、月配额达到上限时返回 true
     */
    @Transactional(readOnly = true)
    public boolean isExceeded(AgentTaskEntity task) {
        UserEntity owner = task == null ? null : task.getOwner();
        if (owner == null) {
            return false;
        }
        UserUsageQuotaResponse quota = current(owner);
        return quota.getDaily().isExceeded() || quota.getWeekly().isExceeded() || quota.getMonthly().isExceeded();
    }

    private UserUsageQuotaPeriodResponse period(UserEntity user, LocalDateTime start, LocalDateTime end,
                                                Long userLimit, Long globalLimit) {
        long used = tokenUsed(user, start, end);
        Long effectiveLimit = userLimit == null ? globalLimit : userLimit;
        UserUsageQuotaPeriodResponse response = new UserUsageQuotaPeriodResponse();
        response.setTokenLimit(effectiveLimit);
        response.setTokenUsed(used);
        response.setLimited(effectiveLimit != null);
        response.setExceeded(effectiveLimit != null && used >= effectiveLimit);
        response.setInherited(userLimit == null && globalLimit != null);
        if (effectiveLimit != null) {
            response.setTokenRemaining(Math.max(effectiveLimit - used, 0L));
            response.setUsagePercent(Math.round(used * 1000.0D / effectiveLimit) / 10.0D);
        }
        return response;
    }

    private long tokenUsed(UserEntity user, LocalDateTime start, LocalDateTime end) {
        if (user == null || user.getId() == null) {
            return 0L;
        }
        Long used = agentTaskRepository.sumTotalTokensByOwnerAndCreatedAt(user.getId(), start, end);
        return used == null ? 0L : used;
    }

    private BizException limitExceeded(String message) {
        return new BizException(ErrorCode.TASK_ERROR, ErrorSubCode.USER_USAGE_LIMIT_EXCEEDED, message);
    }
}
