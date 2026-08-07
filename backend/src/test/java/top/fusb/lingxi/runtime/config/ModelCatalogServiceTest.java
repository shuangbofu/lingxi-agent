package top.fusb.lingxi.runtime.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.enums.UserRole;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.runtime.api.model.RuntimeModelPricing;
import top.fusb.lingxi.runtime.api.model.RuntimeModelProtocol;
import top.fusb.lingxi.runtime.api.model.RuntimePricingScheduleRule;
import top.fusb.lingxi.runtime.api.model.RuntimePricingTimeRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelCatalogServiceTest {

    private final ModelProviderRepository providerRepository = mock(ModelProviderRepository.class);
    private final ModelProfileRepository modelRepository = mock(ModelProfileRepository.class);
    private final ModelPricingPlanRepository pricingRepository = mock(ModelPricingPlanRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final ModelCatalogService service = new ModelCatalogService(
            providerRepository, modelRepository, pricingRepository,
            new RuntimeProviderCatalog(new ObjectMapper()), eventPublisher);
    private final UserEntity user = user(7L, UserRole.USER);

    @BeforeEach
    void setUp() {
        when(modelRepository.findByOwnerIsNullOrderBySortOrderAscNameAsc()).thenReturn(List.of());
        when(modelRepository.findByOwnerIdOrderBySortOrderAscNameAsc(7L)).thenReturn(List.of());
    }

    @Test
    void shouldCombinePlatformAndCurrentUsersModels() {
        ModelProfileEntity platform = model("platform-model", provider("platform-provider", null));
        ModelProfileEntity personal = model("personal-model", provider("personal-provider", user));
        when(modelRepository.findByOwnerIsNullOrderBySortOrderAscNameAsc()).thenReturn(List.of(platform));
        when(modelRepository.findByOwnerIdOrderBySortOrderAscNameAsc(7L)).thenReturn(List.of(personal));

        assertThat(service.visibleModels(user)).extracting(RuntimeModelProfileConfig::getId)
                .containsExactly("platform-model", "personal-model");
    }

    @Test
    void shouldNotResolveAnotherUsersPersonalModelById() {
        when(modelRepository.findByIdAndOwnerId("private-model", 7L)).thenReturn(Optional.empty());
        when(modelRepository.findByIdAndOwnerIsNull("private-model")).thenReturn(Optional.empty());

        assertThat(service.visibleModel(user, "private-model")).isEmpty();
        verify(providerRepository, never()).findByIdAndOwnerId("private-provider", 7L);
    }

    @Test
    void shouldRejectPlatformWriteFromNormalUser() {
        assertThatThrownBy(() -> service.createProvider(
                ModelConfigScope.PLATFORM, new RuntimeProviderRequest(), user))
                .isInstanceOf(BizException.class)
                .hasMessage("无权维护平台模型配置");
    }

    @Test
    void shouldPreferModelPriceOverProviderDefaultPrice() {
        ModelProviderEntity provider = provider("provider", null);
        ModelProfileEntity model = model("model", provider);
        ModelPricingPlanEntity modelPlan = pricingPlan(modelPricing("2"));
        ModelPricingPlanEntity defaultPlan = pricingPlan(modelPricing("1"));
        when(modelRepository.findByIdAndOwnerId("model", 7L)).thenReturn(Optional.empty());
        when(modelRepository.findByIdAndOwnerIsNull("model")).thenReturn(Optional.of(model));
        when(pricingRepository.findFirstByModelId("model")).thenReturn(Optional.of(modelPlan));
        when(pricingRepository.findFirstByProviderIdAndModelIsNull("provider"))
                .thenReturn(Optional.of(defaultPlan));

        assertThat(service.pricingForModel(user, "model")).contains(modelPlan.getPricing());

        when(pricingRepository.findFirstByModelId("model")).thenReturn(Optional.empty());
        assertThat(service.pricingForModel(user, "model")).contains(defaultPlan.getPricing());
    }

    @Test
    void shouldRejectUnsupportedDeepSeekResponsesModel() {
        ModelProviderEntity deepSeek = provider("deepseek-provider", user);
        deepSeek.setProviderType("DEEPSEEK");
        when(providerRepository.findByIdAndOwnerId("deepseek-provider", 7L))
                .thenReturn(Optional.of(deepSeek));
        RuntimeModelProfileRequest request = new RuntimeModelProfileRequest();
        request.setName("DeepSeek Chat");
        request.setProviderId("deepseek-provider");
        request.setModel("deepseek-chat");
        request.setProtocol(RuntimeModelProtocol.RESPONSES);
        request.setContextWindowTokens(128_000);
        request.setEnabled(true);

        assertThatThrownBy(() -> service.createModel(ModelConfigScope.PERSONAL, request, user))
                .isInstanceOf(BizException.class)
                .hasMessage("DeepSeek Responses 协议当前仅支持 deepseek-v4-flash 模型");
    }

    @Test
    void shouldNormalizeMultiplePricingRangesAndRejectOverlaps() {
        UserEntity admin = user(1L, UserRole.ADMIN);
        ModelProviderEntity provider = provider("deepseek-provider", null);
        when(providerRepository.findByIdAndOwnerIsNull("deepseek-provider")).thenReturn(Optional.of(provider));
        when(pricingRepository.findAll()).thenReturn(List.of());
        when(pricingRepository.save(any(ModelPricingPlanEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        RuntimePricingPlanConfig request = new RuntimePricingPlanConfig();
        request.setName("DeepSeek 官方价格");
        request.setProviderId("deepseek-provider");
        request.setPricing(new RuntimeModelPricing("CNY", "Asia/Shanghai",
                new BigDecimal("0.02"), BigDecimal.ONE, new BigDecimal("2"),
                List.of(new RuntimePricingScheduleRule("高峰价", new BigDecimal("2"), List.of(
                        new RuntimePricingTimeRange("09:00", "12:00"),
                        new RuntimePricingTimeRange("14:00", "18:00"))))));

        RuntimePricingPlanConfig saved = service.createPricingPlan(ModelConfigScope.PLATFORM, request, admin);

        assertThat(saved.getPricing().scheduleRules()).singleElement().satisfies(rule -> {
            assertThat(rule.name()).isEqualTo("高峰价");
            assertThat(rule.multiplier()).isEqualByComparingTo("2");
            assertThat(rule.timeRanges()).hasSize(2);
        });

        request.setPricing(new RuntimeModelPricing("CNY", "Asia/Shanghai",
                new BigDecimal("0.02"), BigDecimal.ONE, new BigDecimal("2"),
                List.of(new RuntimePricingScheduleRule("高峰价", new BigDecimal("2"), List.of(
                        new RuntimePricingTimeRange("09:00", "12:00"),
                        new RuntimePricingTimeRange("11:30", "13:00"))))));
        assertThatThrownBy(() -> service.createPricingPlan(ModelConfigScope.PLATFORM, request, admin))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("计价时段存在重叠");
    }

    @Test
    void shouldReadLegacyPricingSnapshotsWithoutRemovedFields() throws Exception {
        RuntimeModelPricing pricing = new ObjectMapper().readValue("""
                {"provider":"DEEPSEEK","currency":"CNY","timeZone":"Asia/Shanghai",
                 "cacheHitInputPerMillion":0.2,"cacheMissInputPerMillion":2,"outputPerMillion":3,
                 "offPeakStart":"00:30","offPeakEnd":"08:30",
                 "offPeakCacheHitInputPerMillion":0.1,"offPeakCacheMissInputPerMillion":1,
                 "offPeakOutputPerMillion":1.5}
                """, RuntimeModelPricing.class);

        assertThat(pricing.cacheMissInputPerMillion()).isEqualByComparingTo("2");
        assertThat(pricing.scheduleRules()).isNull();
    }

    private UserEntity user(Long id, UserRole role) {
        UserEntity entity = new UserEntity();
        entity.setId(id);
        entity.setRole(role);
        return entity;
    }

    private ModelProviderEntity provider(String id, UserEntity owner) {
        ModelProviderEntity provider = new ModelProviderEntity();
        provider.setId(id);
        provider.setOwner(owner);
        provider.setProviderType("OPENAI");
        provider.setName(id);
        provider.setBaseUrl("https://example.test/v1");
        provider.setApiKey("secret-key");
        provider.setEnabled(true);
        provider.setSortOrder(0);
        return provider;
    }

    private ModelProfileEntity model(String id, ModelProviderEntity provider) {
        ModelProfileEntity model = new ModelProfileEntity();
        model.setId(id);
        model.setOwner(provider.getOwner());
        model.setProvider(provider);
        model.setName(id);
        model.setModel(id);
        model.setProtocol(RuntimeModelProtocol.RESPONSES);
        model.setContextWindowTokens(128_000);
        model.setEnabled(true);
        model.setSortOrder(0);
        return model;
    }

    private ModelPricingPlanEntity pricingPlan(RuntimeModelPricing pricing) {
        ModelPricingPlanEntity plan = new ModelPricingPlanEntity();
        plan.setPricing(pricing);
        return plan;
    }

    private RuntimeModelPricing modelPricing(String output) {
        return new RuntimeModelPricing("CNY", "Asia/Shanghai",
                BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal(output), List.of());
    }
}
