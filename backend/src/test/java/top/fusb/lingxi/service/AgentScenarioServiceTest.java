package top.fusb.lingxi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.fusb.lingxi.definition.ModuleDefinition;
import top.fusb.lingxi.definition.CapabilityCommandExtensionDefinition;
import top.fusb.lingxi.definition.LingxiCapabilityDefinition;
import top.fusb.lingxi.dto.AgentScenarioStateUpdateRequest;
import top.fusb.lingxi.entity.AgentScenarioEntity;
import top.fusb.lingxi.enums.ScenarioInputMode;
import top.fusb.lingxi.repository.AgentScenarioRepository;

import java.time.LocalDateTime;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentScenarioServiceTest {

    @Mock
    private AgentScenarioRepository repository;

    @Mock
    private ModuleDefinitionService moduleDefinitionService;

    @Mock
    private AgentDefinitionSupport definitionSupport;

    @Mock
    private DefinitionParameterOptionService definitionParameterOptionService;

    private AgentScenarioService service;

    @BeforeEach
    void setUp() {
        service = new AgentScenarioService(repository, moduleDefinitionService, definitionSupport,
                definitionParameterOptionService);
    }

    @Test
    void shouldPreserveMutableStateWhenDefinitionIsResynchronized() {
        AgentScenarioEntity existing = state("sample", 42);
        existing.setEnabled(false);
        existing.setUserVisible(false);
        when(repository.findById("sample")).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        AgentScenarioEntity result = service.syncDefinition(definition("sample"));

        assertThat(result.getCode()).isEqualTo("sample");
        assertThat(result.getSortOrder()).isEqualTo(42);
        assertThat(result.isEnabled()).isFalse();
        assertThat(result.isUserVisible()).isFalse();
        verify(repository, never()).findMaxSortOrder();
    }

    @Test
    void shouldCreateOnlyScenarioStateForNewDefinition() {
        ModuleDefinition definition = definition("sample");
        definition.setEnabled(true);
        definition.setUserVisible(true);
        definition.setCapabilities(Set.of("project-hub"));
        definition.setCapabilityCommands(Map.of("project-hub", Set.of("project-hub.project-list")));
        when(repository.findById("sample")).thenReturn(Optional.empty());
        when(repository.findMaxSortOrder()).thenReturn(6);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.syncDefinition(definition);

        ArgumentCaptor<AgentScenarioEntity> saved = ArgumentCaptor.forClass(AgentScenarioEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getCode()).isEqualTo("sample");
        assertThat(saved.getValue().getSortOrder()).isEqualTo(7);
        assertThat(saved.getValue().isEnabled()).isTrue();
        assertThat(saved.getValue().isUserVisible()).isTrue();
        assertThat(saved.getValue().getCapabilities()).containsExactly("project-hub");
        assertThat(saved.getValue().getCapabilityCommands())
                .containsEntry("project-hub", Set.of("project-hub.project-list"));
    }

    @Test
    void shouldEnableAllPublicCommandsWhenScenarioDoesNotDeclareAWhitelist() {
        ModuleDefinition scenarioDefinition = definition("sample");
        scenarioDefinition.setCapabilities(Set.of("project-hub"));
        ModuleDefinition capabilityDefinition = definition("project-hub");
        capabilityDefinition.setModuleDirectory(Path.of("project-hub"));
        CapabilityCommandExtensionDefinition listCommand = new CapabilityCommandExtensionDefinition();
        listCommand.setCommand("project-hub project-list");
        CapabilityCommandExtensionDefinition infoCommand = new CapabilityCommandExtensionDefinition();
        infoCommand.setCommand("project-hub project-info");
        LingxiCapabilityDefinition extension = new LingxiCapabilityDefinition();
        extension.setCommands(List.of(listCommand, infoCommand));
        when(repository.findById("sample")).thenReturn(Optional.empty());
        when(repository.findMaxSortOrder()).thenReturn(0);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(moduleDefinitionService.listCapabilities()).thenReturn(List.of(capabilityDefinition));
        when(moduleDefinitionService.readCapabilityExtension(Path.of("project-hub"))).thenReturn(extension);

        AgentScenarioEntity result = service.syncDefinition(scenarioDefinition);

        assertThat(result.getCapabilityCommands()).containsEntry("project-hub",
                Set.of("project-hub.project-list", "project-hub.project-info"));
    }

    @Test
    void shouldPersistSelectedCapabilityAndCommandCodes() {
        AgentScenarioEntity state = state("sample", 1);
        ModuleDefinition scenarioDefinition = definition("sample");
        ModuleDefinition capabilityDefinition = definition("project-hub");
        capabilityDefinition.setModuleDirectory(Path.of("project-hub"));
        CapabilityCommandExtensionDefinition command = new CapabilityCommandExtensionDefinition();
        command.setCommand("project-hub project-list");
        LingxiCapabilityDefinition extension = new LingxiCapabilityDefinition();
        extension.setCommands(List.of(command));
        AgentScenarioStateUpdateRequest request = new AgentScenarioStateUpdateRequest();
        request.setEnabled(true);
        request.setUserVisible(true);
        request.setCapabilities(Set.of("project-hub"));
        request.setCapabilityCommands(Map.of("project-hub", Set.of("project-hub.project-list")));
        when(moduleDefinitionService.listInstalledScenarios()).thenReturn(List.of(scenarioDefinition));
        when(moduleDefinitionService.listCapabilities()).thenReturn(List.of(capabilityDefinition));
        when(moduleDefinitionService.readCapabilityExtension(Path.of("project-hub"))).thenReturn(extension);
        when(repository.findById("sample")).thenReturn(Optional.of(state));
        when(repository.save(state)).thenReturn(state);

        var response = service.update("sample", request);

        assertThat(state.getCapabilities()).containsExactly("project-hub");
        assertThat(state.getCapabilityCommands())
                .containsEntry("project-hub", Set.of("project-hub.project-list"));
        assertThat(response.getCapabilities()).containsExactly("project-hub");
    }

    @Test
    void shouldBuildTaskDefinitionWithCurrentRuntimeConfiguration() {
        ModuleDefinition installed = definition("sample");
        installed.setCapabilities(Set.of("manifest-default"));
        AgentScenarioEntity state = state("sample", 1);
        state.setCapabilities(Set.of("project-hub"));
        state.setCapabilityCommands(Map.of("project-hub", Set.of("project-hub.project-list")));
        when(moduleDefinitionService.listInstalledScenarios()).thenReturn(List.of(installed));
        when(moduleDefinitionService.readPrompt(installed)).thenReturn("场景提示词");
        when(repository.findById("sample")).thenReturn(Optional.of(state));

        var result = service.requireTaskDefinition("sample");

        assertThat(result.getCapabilities()).containsExactly("project-hub");
        assertThat(result.getCapabilityCommands())
                .containsEntry("project-hub", Set.of("project-hub.project-list"));
    }

    @Test
    void shouldReadFixedScenarioFieldsFromInstalledDefinition() {
        AgentScenarioEntity state = state("document-understanding", 1);
        state.setEnabled(true);
        state.setUserVisible(true);
        ModuleDefinition definition = definition("document-understanding");
        definition.setName("文档分析");
        definition.setSlogan("从文档中提取可信答案");
        definition.setDescription("分析文档内容");
        definition.setIcon("icon.svg");
        definition.setColor("#2563eb");
        definition.setCapabilities(Set.of("wiki-ingest"));
        when(repository.findAllByEnabledTrueAndUserVisibleTrueOrderBySortOrderAsc()).thenReturn(List.of(state));
        when(moduleDefinitionService.listInstalledScenarios()).thenReturn(List.of(definition));

        var result = service.listPublicPreviews();

        assertThat(result).singleElement().satisfies(preview -> {
            assertThat(preview.getCode()).isEqualTo("document-understanding");
            assertThat(preview.getName()).isEqualTo("文档分析");
            assertThat(preview.getSlogan()).isEqualTo("从文档中提取可信答案");
            assertThat(preview.getIconUrl()).startsWith(
                    "/api/definition-assets/scenarios/document-understanding/icon?v=");
            assertThat(preview.getColor()).isEqualTo("#2563eb");
        });
    }

    @Test
    void shouldExposeFormInputModeAndDefaultLegacyScenariosToConversation() {
        ModuleDefinition conversation = definition("conversation");
        ModuleDefinition form = definition("form");
        form.setInputMode(ScenarioInputMode.FORM);
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(
                state("conversation", 1), state("form", 2)));
        when(moduleDefinitionService.listInstalledScenarios()).thenReturn(List.of(conversation, form));

        var result = service.listAll();

        assertThat(result).extracting(item -> item.getCode() + ":" + item.getInputMode())
                .containsExactly("conversation:CONVERSATION", "form:FORM");
    }

    @Test
    void shouldPersistCompleteScenarioOrderByCode() {
        AgentScenarioEntity first = state("first", 10);
        AgentScenarioEntity second = state("second", 20);
        when(repository.findAllByOrderBySortOrderAsc())
                .thenReturn(List.of(first, second), List.of(second, first));
        when(moduleDefinitionService.listInstalledScenarios()).thenReturn(List.of(
                definition("first"), definition("second")));

        var result = service.reorder(List.of("second", "first"));

        assertThat(result).extracting(item -> item.getCode()).containsExactly("second", "first");
        assertThat(second.getSortOrder()).isEqualTo(1);
        assertThat(first.getSortOrder()).isEqualTo(2);
        verify(repository).saveAll(any());
    }

    @Test
    void shouldRejectIncompleteScenarioCodeOrder() {
        when(repository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(
                state("first", 10), state("second", 20)));

        assertThatThrownBy(() -> service.reorder(List.of("first")))
                .hasMessageContaining("场景列表已变化");
        verify(repository, never()).saveAll(any());
    }

    private ModuleDefinition definition(String code) {
        ModuleDefinition definition = new ModuleDefinition();
        definition.setCode(code);
        definition.setName(code);
        definition.setScenario("TEST");
        definition.setEnabled(true);
        definition.setParameters(List.of());
        definition.setGuides(List.of());
        return definition;
    }

    private AgentScenarioEntity state(String code, int sortOrder) {
        AgentScenarioEntity scenario = new AgentScenarioEntity();
        scenario.setCode(code);
        scenario.setSortOrder(sortOrder);
        scenario.setCreatedAt(LocalDateTime.now());
        scenario.setUpdatedAt(LocalDateTime.now());
        return scenario;
    }
}
