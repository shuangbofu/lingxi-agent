package top.fusb.lingxi.auth;

import top.fusb.lingxi.dto.UserUsageQuotaResponse;
import top.fusb.lingxi.runtime.config.RuntimeConfigService;
import top.fusb.lingxi.runtime.config.RuntimeConfigEntity;
import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.repository.AgentTaskRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserUsageQuotaServiceTest {

    @Test
    void shouldApplyUserQuotaAndInheritGlobalQuota() {
        AgentTaskRepository repository = mock(AgentTaskRepository.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        UserUsageQuotaService service = new UserUsageQuotaService(repository, runtimeConfigService);
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setDailyTokenLimit(100L);
        RuntimeConfigEntity global = new RuntimeConfigEntity();
        global.setGlobalWeeklyTokenLimit(1000L);
        when(runtimeConfigService.getEntity()).thenReturn(global);
        when(repository.sumTotalTokensByOwnerAndCreatedAt(eq(7L), any(), any())).thenReturn(125L);

        UserUsageQuotaResponse response = service.current(user);

        assertTrue(response.getDaily().isExceeded());
        assertEquals(125L, response.getDaily().getTokenUsed());
        assertEquals(0L, response.getDaily().getTokenRemaining());
        assertEquals(125.0D, response.getDaily().getUsagePercent());
        assertTrue(response.getWeekly().isInherited());
        assertFalse(response.getWeekly().isExceeded());
        assertFalse(response.getMonthly().isLimited());
        BizException exception = assertThrows(BizException.class, () -> service.requireAvailable(user));
        assertEquals(ErrorSubCode.USER_USAGE_LIMIT_EXCEEDED, exception.getErrorSubCode());
    }
}
