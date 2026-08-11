package top.fusb.lingxi.service;

import org.junit.jupiter.api.Test;
import top.fusb.lingxi.definition.ModuleDefinition;
import top.fusb.lingxi.entity.AgentCapabilityEntity;
import top.fusb.lingxi.entity.AgentScenarioEntity;
import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.enums.TaskStatus;
import top.fusb.lingxi.repository.AgentCapabilityRepository;
import top.fusb.lingxi.repository.AgentScenarioRepository;
import top.fusb.lingxi.repository.AgentTaskRepository;
import top.fusb.lingxi.repository.CapabilityConfigRepository;
import top.fusb.lingxi.resource.ResourceCatalogItemRepository;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCapabilityServiceTest {

    @Test
    void returnsOnlyEnabledCapabilitiesWithInstalledSkillFiles() {
        AgentCapabilityRepository repository = mock(AgentCapabilityRepository.class);
        ModuleDefinitionService moduleDefinitionService = mock(ModuleDefinitionService.class);
        AgentCapabilityService service = service(repository, moduleDefinitionService);
        Set<String> requested = new LinkedHashSet<>(List.of(
                "project-hub", "missing-skill", "disabled-skill", "code-repository"));
        when(moduleDefinitionService.listCapabilities()).thenReturn(List.of(
                module("project-hub"), module("disabled-skill"), module("code-repository")));
        when(repository.findAllByCodeIn(requested)).thenReturn(List.of(
                capability("code-repository", true), capability("project-hub", true),
                capability("missing-skill", true), capability("disabled-skill", false)));

        assertThat(service.availableRuntimeCodes(requested))
                .containsExactly("project-hub", "code-repository");
    }

    @Test
    void shouldPreserveEnabledStateWhenDefinitionIsResynchronized() {
        AgentCapabilityRepository repository = mock(AgentCapabilityRepository.class);
        AgentCapabilityEntity existing = capability("project-hub", false);
        existing.setCreatedAt(LocalDateTime.now());
        when(repository.findById("project-hub")).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        AgentCapabilityEntity result = service(repository, mock(ModuleDefinitionService.class))
                .syncDefinition(module("project-hub"));

        assertThat(result.getCode()).isEqualTo("project-hub");
        assertThat(result.isEnabled()).isFalse();
    }

    @Test
    void shouldCreateOnlyCapabilityStateForNewDefinition() {
        AgentCapabilityRepository repository = mock(AgentCapabilityRepository.class);
        ModuleDefinition definition = module("project-hub");
        definition.setEnabled(true);
        when(repository.findById("project-hub")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service(repository, mock(ModuleDefinitionService.class)).syncDefinition(definition);

        verify(repository).save(any(AgentCapabilityEntity.class));
        verify(repository, never()).findAllByOrderByCodeAsc();
    }

    @Test
    void shouldUninstallDisabledCapabilityAndCleanDependentState() {
        AgentCapabilityRepository repository = mock(AgentCapabilityRepository.class);
        AgentScenarioRepository scenarioRepository = mock(AgentScenarioRepository.class);
        AgentTaskRepository taskRepository = mock(AgentTaskRepository.class);
        CapabilityConfigRepository configRepository = mock(CapabilityConfigRepository.class);
        ResourceCatalogItemRepository resourceRepository = mock(ResourceCatalogItemRepository.class);
        AgentCapabilityEntity capability = capability("project-hub", false);
        AgentScenarioEntity scenario = new AgentScenarioEntity();
        scenario.setCapabilities(new LinkedHashSet<>(Set.of("project-hub", "other")));
        scenario.setCapabilityCommands(new java.util.LinkedHashMap<>(Map.of(
                "project-hub", Set.of("project-hub.list"),
                "other", Set.of("other.run"))));
        when(repository.findById("project-hub")).thenReturn(Optional.of(capability));
        when(taskRepository.findByStatusIn(any())).thenReturn(List.of());
        when(scenarioRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(scenario));

        service(repository, scenarioRepository, taskRepository, configRepository, resourceRepository)
                .uninstallState("project-hub");

        assertThat(scenario.getCapabilities()).containsExactly("other");
        assertThat(scenario.getCapabilityCommands()).containsOnlyKeys("other");
        verify(scenarioRepository).saveAll(List.of(scenario));
        verify(configRepository).deleteByCapabilityCode("project-hub");
        verify(resourceRepository).deleteByProviderCode("project-hub");
        verify(repository).delete(capability);
    }

    @Test
    void shouldRejectUninstallWhenActiveTaskUsesCapability() {
        AgentCapabilityRepository repository = mock(AgentCapabilityRepository.class);
        AgentScenarioRepository scenarioRepository = mock(AgentScenarioRepository.class);
        AgentTaskRepository taskRepository = mock(AgentTaskRepository.class);
        CapabilityConfigRepository configRepository = mock(CapabilityConfigRepository.class);
        ResourceCatalogItemRepository resourceRepository = mock(ResourceCatalogItemRepository.class);
        AgentTaskEntity task = new AgentTaskEntity();
        task.setStatus(TaskStatus.RUNNING);
        task.setEnabledCapabilityCodes(Set.of("project-hub"));
        when(repository.findById("project-hub")).thenReturn(Optional.of(capability("project-hub", false)));
        when(taskRepository.findByStatusIn(any())).thenReturn(List.of(task));

        assertThatThrownBy(() -> service(repository, scenarioRepository, taskRepository,
                configRepository, resourceRepository).uninstallState("project-hub"))
                .hasMessageContaining("未结束任务");

        verify(scenarioRepository, never()).findAllByOrderBySortOrderAsc();
        verify(configRepository, never()).deleteByCapabilityCode(any());
        verify(repository, never()).delete(any());
    }

    private AgentCapabilityService service(AgentCapabilityRepository repository,
                                           ModuleDefinitionService moduleDefinitionService) {
        return service(repository, mock(AgentScenarioRepository.class), mock(AgentTaskRepository.class),
                mock(CapabilityConfigRepository.class), mock(ResourceCatalogItemRepository.class),
                moduleDefinitionService);
    }

    private AgentCapabilityService service(AgentCapabilityRepository repository,
                                           AgentScenarioRepository scenarioRepository,
                                           AgentTaskRepository taskRepository,
                                           CapabilityConfigRepository configRepository,
                                           ResourceCatalogItemRepository resourceRepository) {
        return service(repository, scenarioRepository, taskRepository, configRepository,
                resourceRepository, mock(ModuleDefinitionService.class));
    }

    private AgentCapabilityService service(AgentCapabilityRepository repository,
                                           AgentScenarioRepository scenarioRepository,
                                           AgentTaskRepository taskRepository,
                                           CapabilityConfigRepository configRepository,
                                           ResourceCatalogItemRepository resourceRepository,
                                           ModuleDefinitionService moduleDefinitionService) {
        return new AgentCapabilityService(repository, scenarioRepository,
                taskRepository, configRepository, resourceRepository, moduleDefinitionService,
                mock(AgentDefinitionSupport.class));
    }

    private ModuleDefinition module(String code) {
        ModuleDefinition definition = new ModuleDefinition();
        definition.setCode(code);
        definition.setEnabled(true);
        return definition;
    }

    private AgentCapabilityEntity capability(String code, boolean enabled) {
        AgentCapabilityEntity capability = new AgentCapabilityEntity();
        capability.setCode(code);
        capability.setEnabled(enabled);
        return capability;
    }
}
