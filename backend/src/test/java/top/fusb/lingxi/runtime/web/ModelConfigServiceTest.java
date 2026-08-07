package top.fusb.lingxi.runtime.web;

import top.fusb.lingxi.dto.ModelEndpointCheckRequest;
import top.fusb.lingxi.dto.ModelEndpointCheckResponse;
import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.runtime.config.ModelCatalogService;
import top.fusb.lingxi.runtime.config.RuntimeProviderConfig;
import top.fusb.lingxi.runtime.model.ModelEndpointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelConfigServiceTest {

    private final ModelCatalogService modelCatalogService = mock(ModelCatalogService.class);
    private final ModelEndpointService endpointService = mock(ModelEndpointService.class);
    private final ModelConfigService service = new ModelConfigService(modelCatalogService, endpointService);
    private final UserEntity user = new UserEntity();

    @BeforeEach
    void setUp() {
        user.setId(7L);
    }

    @Test
    void shouldCheckUnsavedEndpointInput() {
        ModelEndpointCheckRequest request = new ModelEndpointCheckRequest();
        request.setApiKey("sk-personal-test");
        request.setBaseUrl("https://example.test/v1");
        when(modelCatalogService.visibleModel(user, null)).thenReturn(Optional.empty());
        when(modelCatalogService.visibleProvider(user, null)).thenReturn(Optional.empty());
        ModelEndpointCheckResponse expected = response();
        when(endpointService.check("sk-personal-test", "https://example.test/v1", List.of()))
                .thenReturn(expected);

        ModelEndpointCheckResponse result = service.check(request, user);

        assertThat(result).isSameAs(expected);
        verify(endpointService).check("sk-personal-test", "https://example.test/v1", List.of());
    }

    @Test
    void shouldUseCurrentUsersSavedProvider() {
        RuntimeProviderConfig provider = new RuntimeProviderConfig();
        provider.setId("deepseek");
        provider.setBaseUrl("https://api.deepseek.com");
        provider.setApiKey("provider-key");
        when(modelCatalogService.visibleProvider(user, "deepseek")).thenReturn(Optional.of(provider));
        when(modelCatalogService.configuredModels(user, "deepseek")).thenReturn(List.of("deepseek-chat"));
        when(endpointService.check("provider-key", "https://api.deepseek.com", List.of("deepseek-chat")))
                .thenReturn(response());
        ModelEndpointCheckRequest request = new ModelEndpointCheckRequest();
        request.setProviderId("deepseek");

        ModelEndpointCheckResponse result = service.check(request, user);

        assertThat(result.isSuccess()).isTrue();
        verify(endpointService).check("provider-key", "https://api.deepseek.com", List.of("deepseek-chat"));
    }

    @Test
    void shouldNotReadAnotherUsersProviderByGuessedId() {
        when(modelCatalogService.visibleProvider(user, "other-user-provider")).thenReturn(Optional.empty());
        when(endpointService.check(null, null, List.of())).thenReturn(response());
        ModelEndpointCheckRequest request = new ModelEndpointCheckRequest();
        request.setProviderId("other-user-provider");

        service.check(request, user);

        verify(endpointService).check(null, null, List.of());
    }

    private ModelEndpointCheckResponse response() {
        ModelEndpointCheckResponse response = new ModelEndpointCheckResponse();
        response.setSuccess(true);
        response.setMessage("检测通过");
        response.setModels(List.of("deepseek-chat"));
        return response;
    }
}
