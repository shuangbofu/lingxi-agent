package top.fusb.lingxi.runtime.capability;

import top.fusb.lingxi.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRuntimeAccessServiceTest {

    private final AgentRuntimeAccessService service = new AgentRuntimeAccessService();

    @Test
    void shouldBindTokenToTaskAndCapabilities() {
        String token = service.issue(11L, Set.of("capability-a"), Set.of("/api/agent-runtime/custom"));

        AgentRuntimeAccessService.RuntimeGrant grant = service.require(token, 11L);

        assertEquals(11L, grant.taskId());
        assertEquals(Set.of("capability-a"), grant.capabilityCodes());
        service.requirePath(grant, "/api/agent-runtime/custom/items");
        assertThrows(BizException.class, () -> service.requirePath(grant, "/api/agent-runtime/other"));
        assertThrows(BizException.class, () -> service.require(token, 12L));
    }

    @Test
    void shouldRejectMissingAndRevokedToken() {
        assertThrows(BizException.class, () -> service.require(null, 11L));
        String token = service.issue(11L, Set.of(), Set.of());

        service.revoke(11L);

        assertThrows(BizException.class, () -> service.require(token, 11L));
    }
}
