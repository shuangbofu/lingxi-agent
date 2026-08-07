package top.fusb.lingxi.runtime.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.config.LingxiProperties;
import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.runtime.api.model.RuntimeAvailability;
import top.fusb.lingxi.runtime.api.model.RuntimeDescriptor;
import top.fusb.lingxi.runtime.api.model.RuntimeModelProtocol;
import top.fusb.lingxi.runtime.core.AgentRuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeModeServiceTest {

    private final AgentRuntimeService agentRuntimeService = mock(AgentRuntimeService.class);
    private final ModelCatalogService modelCatalogService = mock(ModelCatalogService.class);
    private final LingxiProperties lingxiProperties = new LingxiProperties();
    private final RuntimeProviderCatalog providerCatalog = new RuntimeProviderCatalog(new ObjectMapper());
    private final RuntimeModeService service = new RuntimeModeService(
            agentRuntimeService, modelCatalogService, lingxiProperties, providerCatalog);
    private final UserEntity user = new UserEntity();
    private RuntimeModelProfileConfig platformModel;

    @BeforeEach
    void setUp() {
        user.setId(7L);
        platformModel = modelProfile("gpt-profile", "GPT Test", RuntimeModelProtocol.RESPONSES);
        when(modelCatalogService.visibleModels(user)).thenReturn(List.of(platformModel));
        when(modelCatalogService.visibleModel(user, "gpt-profile")).thenReturn(Optional.of(platformModel));
        when(modelCatalogService.platformModel("gpt-profile")).thenReturn(Optional.of(platformModel));
        when(modelCatalogService.visibleProvider(user, "responses-provider")).thenReturn(Optional.of(provider()));
        when(agentRuntimeService.descriptors()).thenReturn(List.of(
                new RuntimeDescriptor("codex", "Codex", "完整执行", "openai", true, true,
                        Set.of(RuntimeModelProtocol.RESPONSES)),
                new RuntimeDescriptor("langchain", "灵析自研", "内置执行", "lingxi", true, false,
                        Set.of(RuntimeModelProtocol.RESPONSES, RuntimeModelProtocol.CHAT_COMPLETIONS))
        ));
        when(agentRuntimeService.availability("codex")).thenReturn(RuntimeAvailability.ready());
        when(agentRuntimeService.availability("langchain")).thenReturn(RuntimeAvailability.ready());
        when(agentRuntimeService.defaultCode()).thenReturn("codex");
        when(agentRuntimeService.requireCode("codex")).thenReturn("codex");
    }

    @Test
    void shouldExposeRuntimeMetadataAndPlatformModel() {
        List<RuntimeModeResponse> options = service.options(user);

        assertThat(options).hasSize(2);
        assertThat(options.get(0).isDefaultSelected()).isTrue();
        assertThat(options.get(0).getModels()).first().satisfies(model -> {
            assertThat(model.getProviderName()).isEqualTo("OpenAI 兼容服务");
            assertThat(model.getReasoningEffortLabel()).isEqualTo("高");
            assertThat(model.isPersonal()).isFalse();
        });
        assertThat(options.get(1).getModels()).hasSize(1);
    }

    @Test
    void shouldExposePersonalCompatibleModelOnlyToCurrentUser() {
        RuntimeModelProfileConfig personal = modelProfile(
                "personal-deepseek", "我的 DeepSeek", RuntimeModelProtocol.CHAT_COMPLETIONS);
        when(modelCatalogService.visibleModels(user)).thenReturn(List.of(platformModel, personal));
        when(modelCatalogService.visibleProvider(user, "responses-provider")).thenReturn(Optional.of(provider()));
        when(modelCatalogService.platformModel("personal-deepseek")).thenReturn(Optional.empty());

        List<RuntimeModeResponse> options = service.options(user);

        assertThat(options.get(0).getModels()).extracting(RuntimeModelOptionResponse::getName)
                .containsExactly("GPT Test");
        assertThat(options.get(1).getModels()).extracting(RuntimeModelOptionResponse::getName)
                .containsExactly("GPT Test", "我的 DeepSeek");
        assertThat(options.get(1).getModels().get(1).isPersonal()).isTrue();
    }

    @Test
    void shouldKeepRuntimeSelectableWhenModelCredentialIsIncomplete() {
        platformModel.setApiKey(null);

        assertThat(service.options(user)).allSatisfy(option -> {
            assertThat(option.isAvailable()).isTrue();
            assertThat(option.getModels()).allSatisfy(model -> {
                assertThat(model.isAvailable()).isFalse();
                assertThat(model.getUnavailableReason()).isEqualTo("模型 API Key 未配置");
            });
        });
    }

    @Test
    void shouldExposeProviderTypeTabsWithoutModels() {
        when(modelCatalogService.visibleModels(user)).thenReturn(List.of());

        List<RuntimeModeResponse> options = service.options(user);

        assertThat(options).allSatisfy(option -> assertThat(option.isAvailable()).isTrue());
        assertThat(options.get(0).getModelProviderTypes()).extracting(RuntimeProviderTypeOption::value)
                .containsExactly("OPENAI", "DEEPSEEK");
        assertThat(options.get(1).getModelProviderTypes()).extracting(RuntimeProviderTypeOption::value)
                .containsExactly("OPENAI", "DEEPSEEK", "KIMI");
    }

    @Test
    void shouldRejectUnavailableRuntime() {
        when(agentRuntimeService.availability("langchain"))
                .thenReturn(RuntimeAvailability.unavailable("执行引擎未安装"));

        assertThatThrownBy(() -> service.requireAvailable("langchain", user))
                .isInstanceOf(BizException.class)
                .hasMessage("执行引擎未安装");
    }

    private RuntimeModelProfileConfig modelProfile(String id, String name, RuntimeModelProtocol protocol) {
        RuntimeModelProfileConfig profile = new RuntimeModelProfileConfig();
        profile.setId(id);
        profile.setName(name);
        profile.setProviderId("responses-provider");
        profile.setBaseUrl("https://example.test/v1");
        profile.setApiKey("system-key");
        profile.setModel(id);
        profile.setProtocol(protocol);
        profile.setContextWindowTokens(128_000);
        profile.setReasoningEffort("high");
        profile.setEnabled(true);
        return profile;
    }

    private RuntimeProviderConfig provider() {
        RuntimeProviderConfig provider = new RuntimeProviderConfig();
        provider.setId("responses-provider");
        provider.setProviderType("OPENAI");
        provider.setName("OpenAI 兼容服务");
        provider.setBaseUrl("https://api.openai.com/v1");
        RuntimeReasoningEffortOption high = new RuntimeReasoningEffortOption();
        high.setLabel("高");
        high.setValue("high");
        provider.setReasoningEffortOptions(List.of(high));
        return provider;
    }
}
