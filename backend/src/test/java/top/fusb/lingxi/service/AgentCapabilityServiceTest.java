package top.fusb.lingxi.service;

import org.junit.jupiter.api.Test;
import top.fusb.lingxi.definition.ModuleDefinition;
import top.fusb.lingxi.entity.AgentCapabilityEntity;
import top.fusb.lingxi.repository.AgentCapabilityRepository;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
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

    private AgentCapabilityService service(AgentCapabilityRepository repository,
                                           ModuleDefinitionService moduleDefinitionService) {
        return new AgentCapabilityService(repository, moduleDefinitionService, mock(AgentDefinitionSupport.class));
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
