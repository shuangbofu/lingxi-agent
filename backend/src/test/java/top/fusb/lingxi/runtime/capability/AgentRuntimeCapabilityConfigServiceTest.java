package top.fusb.lingxi.runtime.capability;

import top.fusb.lingxi.definition.ModuleDefinition;
import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.entity.CapabilityConfigEntity;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.repository.AgentTaskRepository;
import top.fusb.lingxi.repository.TaskRuntimeContextValueRepository;
import top.fusb.lingxi.service.CapabilityConfigService;
import top.fusb.lingxi.service.AgentCapabilityService;
import top.fusb.lingxi.task.TaskAgentWorkspaceService;
import top.fusb.lingxi.task.TaskStoragePathService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRuntimeCapabilityConfigServiceTest {

    private CapabilityConfigService capabilityConfigService;
    private AgentCapabilityService agentCapabilityService;
    private AgentTaskRepository agentTaskRepository;
    private AgentRuntimeCapabilityConfigService service;

    @BeforeEach
    void setUp() {
        capabilityConfigService = mock(CapabilityConfigService.class);
        agentCapabilityService = mock(AgentCapabilityService.class);
        agentTaskRepository = mock(AgentTaskRepository.class);
        service = new AgentRuntimeCapabilityConfigService(
                capabilityConfigService,
                agentCapabilityService,
                agentTaskRepository,
                mock(TaskRuntimeContextValueRepository.class),
                mock(TaskAgentWorkspaceService.class),
                mock(TaskStoragePathService.class)
        );
    }

    @Test
    void shouldExposeOnlyConfigsMountedByTaskScenario() {
        AgentTaskEntity task = taskWithCapabilities("allowed-capability");
        CapabilityConfigEntity allowed = config(1L, "allowed-capability");
        CapabilityConfigEntity denied = config(2L, "other-capability");
        when(agentTaskRepository.findWithDetailsById(9L)).thenReturn(Optional.of(task));
        when(agentTaskRepository.findTaskCapabilityCodesByTaskId(9L)).thenReturn(Set.of("allowed-capability"));
        when(capabilityConfigService.listEnabledConfigs()).thenReturn(List.of(allowed, denied));
        when(agentCapabilityService.requireDefinition("allowed-capability"))
                .thenReturn(capabilityDefinition("allowed-capability"));

        var result = service.list(9L);

        assertEquals(1, result.size());
        assertEquals("allowed-capability", result.get(0).getCapabilityCode());
    }

    @Test
    void shouldRejectDetailForUnmountedCapability() {
        AgentTaskEntity task = taskWithCapabilities("allowed-capability");
        CapabilityConfigEntity denied = config(2L, "other-capability");
        when(agentTaskRepository.findWithDetailsById(9L)).thenReturn(Optional.of(task));
        when(agentTaskRepository.findTaskCapabilityCodesByTaskId(9L)).thenReturn(Set.of("allowed-capability"));
        when(capabilityConfigService.requireEnabledConfig(2L)).thenReturn(denied);

        assertThrows(BizException.class, () -> service.detail(2L, 9L));
    }

    private AgentTaskEntity taskWithCapabilities(String... codes) {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setEnabledCapabilityCodes(Set.of(codes));
        return task;
    }

    private CapabilityConfigEntity config(Long id, String capabilityCode) {
        CapabilityConfigEntity config = new CapabilityConfigEntity();
        config.setId(id);
        config.setName(capabilityCode + " config");
        config.setCapabilityCode(capabilityCode);
        config.setEnabled(true);
        return config;
    }

    private ModuleDefinition capabilityDefinition(String code) {
        ModuleDefinition definition = new ModuleDefinition();
        definition.setCode(code);
        definition.setName(code);
        return definition;
    }
}
