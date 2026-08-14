package top.fusb.lingxi.runtime.config;

import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.enums.UserRole;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.kit.SecretValueKit;
import top.fusb.lingxi.kit.TextKit;
import top.fusb.lingxi.runtime.api.model.RuntimeModelPricing;
import top.fusb.lingxi.runtime.api.model.RuntimeModelProtocol;
import top.fusb.lingxi.runtime.api.model.RuntimePricingScheduleRule;
import top.fusb.lingxi.runtime.api.model.RuntimePricingTimeRange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelCatalogService {

    private static final int DEFAULT_MODEL_CONTEXT_WINDOW = 128_000;
    private static final int MIN_MODEL_CONTEXT_WINDOW = 16_000;
    private static final int MAX_MODEL_CONTEXT_WINDOW = 10_000_000;
    private static final int MAX_INSTRUCTION_PROMPT_LENGTH = 10_000;

    private final ModelProviderRepository providerRepository;
    private final ModelProfileRepository modelRepository;
    private final ModelPricingPlanRepository pricingPlanRepository;
    private final RuntimeProviderCatalog providerCatalog;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 查询指定归属范围的供应商、模型和计价方案。
     *
     * @param scope 平台或个人配置范围
     * @param user 当前登录用户
     * @return 当前范围内的完整模型目录
     * @throws BizException 用户未登录时抛出
     */
    @Transactional(readOnly = true)
    public ModelCatalogResponse catalog(ModelConfigScope scope, UserEntity user) {
        requireUser(user);
        List<ModelProviderEntity> providers = providers(scope, user);
        ModelCatalogResponse response = new ModelCatalogResponse();
        response.setScope(scope);
        response.setProviders(providers.stream().map(this::toProviderResponse).toList());
        response.setModels(models(scope, user).stream().map(this::toModelResponse).toList());
        response.setPricingPlans(pricingPlans(scope, user).stream().map(this::toPricingPlan).toList());
        response.setProviderTypes(providerCatalog.definitions().stream().map(RuntimeProviderTypeOption::from).toList());
        return response;
    }

    /**
     * 新增指定范围的模型供应商。
     *
     * @param scope 平台或个人配置范围
     * @param request 供应商参数
     * @param user 当前登录用户
     * @return 已保存的供应商
     * @throws BizException 参数无效或用户无权维护平台配置时抛出
     */
    @Transactional
    public RuntimeProviderResponse createProvider(ModelConfigScope scope, RuntimeProviderRequest request, UserEntity user) {
        authorizeWrite(scope, user);
        ModelProviderEntity entity = new ModelProviderEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setOwner(owner(scope, user));
        entity.setCreatedAt(LocalDateTime.now());
        applyProvider(entity, request, true);
        ModelProviderEntity saved = providerRepository.save(entity);
        changed();
        log.info("新增模型供应商 providerId={} scope={} ownerId={}", saved.getId(), scope,
                saved.getOwner() == null ? null : saved.getOwner().getId());
        return toProviderResponse(saved);
    }

    /**
     * 修改指定范围的模型供应商。
     *
     * @param scope 平台或个人配置范围
     * @param id 供应商 ID
     * @param request 供应商参数
     * @param user 当前登录用户
     * @return 已保存的供应商
     * @throws BizException 供应商不存在、参数无效或无权操作时抛出
     */
    @Transactional
    public RuntimeProviderResponse updateProvider(ModelConfigScope scope, String id,
                                                  RuntimeProviderRequest request, UserEntity user) {
        authorizeWrite(scope, user);
        ModelProviderEntity entity = requireProvider(scope, id, user);
        applyProvider(entity, request, false);
        ModelProviderEntity saved = providerRepository.save(entity);
        changed();
        log.info("修改模型供应商 providerId={} scope={}", id, scope);
        return toProviderResponse(saved);
    }

    /**
     * 删除没有模型引用的供应商。
     *
     * @param scope 平台或个人配置范围
     * @param id 供应商 ID
     * @param user 当前登录用户
     * @return 无返回值
     * @throws BizException 供应商被模型引用或用户无权操作时抛出
     */
    @Transactional
    public void deleteProvider(ModelConfigScope scope, String id, UserEntity user) {
        authorizeWrite(scope, user);
        ModelProviderEntity entity = requireProvider(scope, id, user);
        if (modelRepository.existsByProviderId(id)) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                    "请先删除该供应商下的模型");
        }
        pricingPlanRepository.deleteByProviderId(id);
        providerRepository.delete(entity);
        changed();
        log.info("删除模型供应商 providerId={} scope={}", id, scope);
    }

    /**
     * 新增指定范围的模型。
     *
     * @param scope 平台或个人配置范围
     * @param request 模型参数
     * @param user 当前登录用户
     * @return 已保存的模型
     * @throws BizException 参数或引用无效时抛出
     */
    @Transactional
    public RuntimeModelProfileResponse createModel(ModelConfigScope scope, RuntimeModelProfileRequest request,
                                                   UserEntity user) {
        authorizeWrite(scope, user);
        ModelProfileEntity entity = new ModelProfileEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setOwner(owner(scope, user));
        entity.setCreatedAt(LocalDateTime.now());
        applyModel(entity, scope, request, user);
        ModelProfileEntity saved = modelRepository.save(entity);
        changed();
        log.info("新增模型 modelProfileId={} scope={} providerId={}", saved.getId(), scope,
                saved.getProvider().getId());
        return toModelResponse(saved);
    }

    /**
     * 修改指定范围的模型。
     *
     * @param scope 平台或个人配置范围
     * @param id 模型 ID
     * @param request 模型参数
     * @param user 当前登录用户
     * @return 已保存的模型
     * @throws BizException 模型不存在、参数无效或无权操作时抛出
     */
    @Transactional
    public RuntimeModelProfileResponse updateModel(ModelConfigScope scope, String id,
                                                   RuntimeModelProfileRequest request, UserEntity user) {
        authorizeWrite(scope, user);
        ModelProfileEntity entity = requireModel(scope, id, user);
        applyModel(entity, scope, request, user);
        ModelProfileEntity saved = modelRepository.save(entity);
        changed();
        log.info("修改模型 modelProfileId={} scope={}", id, scope);
        return toModelResponse(saved);
    }

    /**
     * 删除指定范围的模型。
     *
     * @param scope 平台或个人配置范围
     * @param id 模型 ID
     * @param user 当前登录用户
     * @return 无返回值
     * @throws BizException 用户无权操作时抛出
     */
    @Transactional
    public void deleteModel(ModelConfigScope scope, String id, UserEntity user) {
        authorizeWrite(scope, user);
        ModelProfileEntity entity = requireModel(scope, id, user);
        pricingPlanRepository.deleteByModelId(id);
        modelRepository.delete(entity);
        changed();
        log.info("删除模型 modelProfileId={} scope={}", id, scope);
    }

    /**
     * 新增计价方案。
     *
     * @param scope 平台或个人配置范围
     * @param request 计价方案参数
     * @param user 当前登录用户
     * @return 已保存的计价方案
     * @throws BizException 参数无效或用户无权操作时抛出
     */
    @Transactional
    public RuntimePricingPlanConfig createPricingPlan(ModelConfigScope scope, RuntimePricingPlanConfig request,
                                                       UserEntity user) {
        authorizeWrite(scope, user);
        ModelPricingPlanEntity entity = new ModelPricingPlanEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setCreatedAt(LocalDateTime.now());
        applyPricingPlan(entity, scope, request, user);
        ModelPricingPlanEntity saved = pricingPlanRepository.save(entity);
        changed();
        log.info("新增模型计价方案 pricingPlanId={} scope={}", saved.getId(), scope);
        return toPricingPlan(saved);
    }

    /**
     * 修改计价方案。
     *
     * @param scope 平台或个人配置范围
     * @param id 计价方案 ID
     * @param request 计价方案参数
     * @param user 当前登录用户
     * @return 已保存的计价方案
     * @throws BizException 方案不存在、参数无效或用户无权操作时抛出
     */
    @Transactional
    public RuntimePricingPlanConfig updatePricingPlan(ModelConfigScope scope, String id,
                                                       RuntimePricingPlanConfig request, UserEntity user) {
        authorizeWrite(scope, user);
        ModelPricingPlanEntity entity = requirePricingPlan(scope, id, user);
        applyPricingPlan(entity, scope, request, user);
        ModelPricingPlanEntity saved = pricingPlanRepository.save(entity);
        changed();
        log.info("修改模型计价方案 pricingPlanId={} scope={}", id, scope);
        return toPricingPlan(saved);
    }

    /**
     * 删除计价方案。
     *
     * @param scope 平台或个人配置范围
     * @param id 计价方案 ID
     * @param user 当前登录用户
     * @return 无返回值
     * @throws BizException 方案不存在或用户无权操作时抛出
     */
    @Transactional
    public void deletePricingPlan(ModelConfigScope scope, String id, UserEntity user) {
        authorizeWrite(scope, user);
        ModelPricingPlanEntity entity = requirePricingPlan(scope, id, user);
        pricingPlanRepository.delete(entity);
        changed();
        log.info("删除模型计价方案 pricingPlanId={} scope={}", id, scope);
    }

    /**
     * 返回当前用户可选的平台模型和个人模型。
     *
     * @param user 当前登录用户
     * @return 已解析供应商连接信息的模型配置
     */
    @Transactional(readOnly = true)
    public List<RuntimeModelProfileConfig> visibleModels(UserEntity user) {
        requireUser(user);
        List<ModelProfileEntity> entities = new ArrayList<>(modelRepository.findByOwnerIsNullOrderBySortOrderAscNameAsc());
        entities.addAll(modelRepository.findByOwnerIdOrderBySortOrderAscNameAsc(user.getId()));
        return entities.stream().map(this::resolveModel).toList();
    }

    /**
     * 按 ID 查询当前用户可用的平台或个人模型。
     *
     * @param user 当前登录用户
     * @param modelProfileId 模型 ID
     * @return 已解析供应商连接信息的模型
     */
    @Transactional(readOnly = true)
    public Optional<RuntimeModelProfileConfig> visibleModel(UserEntity user, String modelProfileId) {
        String id = TextKit.blankToNull(modelProfileId);
        if (user == null || id == null) {
            return Optional.empty();
        }
        Optional<ModelProfileEntity> personal = modelRepository.findByIdAndOwnerId(id, user.getId());
        return personal.or(() -> modelRepository.findByIdAndOwnerIsNull(id)).map(this::resolveModel);
    }

    /**
     * 按 ID 查询平台模型。
     *
     * @param modelProfileId 模型 ID
     * @return 已解析供应商连接信息的平台模型
     */
    @Transactional(readOnly = true)
    public Optional<RuntimeModelProfileConfig> platformModel(String modelProfileId) {
        String id = TextKit.blankToNull(modelProfileId);
        return id == null ? Optional.empty() : modelRepository.findByIdAndOwnerIsNull(id).map(this::resolveModel);
    }

    /**
     * 查询模型价格，模型专属价格优先于供应商默认价格。
     *
     * @param user 当前登录用户
     * @param modelProfileId 模型 ID
     * @return 当前用户可访问的价格配置
     */
    @Transactional(readOnly = true)
    public Optional<RuntimeModelPricing> pricingForModel(UserEntity user, String modelProfileId) {
        String id = TextKit.blankToNull(modelProfileId);
        if (user == null || id == null) {
            return Optional.empty();
        }
        Optional<ModelProfileEntity> model = modelRepository.findByIdAndOwnerId(id, user.getId())
                .or(() -> modelRepository.findByIdAndOwnerIsNull(id));
        return model.flatMap(entity -> pricingPlanRepository.findFirstByModelId(entity.getId())
                .or(() -> pricingPlanRepository.findFirstByProviderIdAndModelIsNull(entity.getProvider().getId())))
                .map(ModelPricingPlanEntity::getPricing);
    }

    /**
     * 返回平台模型列表。
     *
     * @return 已解析的平台模型列表
     */
    @Transactional(readOnly = true)
    public List<RuntimeModelProfileConfig> platformModels() {
        return modelRepository.findByOwnerIsNullOrderBySortOrderAscNameAsc().stream().map(this::resolveModel).toList();
    }

    /**
     * 读取所有供应商和模型的并行度规则。
     *
     * @param globalLimit 平台总并行度
     * @return 可供任务调度器直接使用的并行度策略
     */
    @Transactional(readOnly = true)
    public RuntimeConcurrencyPolicy concurrencyPolicy(int globalLimit) {
        LinkedHashMap<String, Integer> providerLimits = new LinkedHashMap<>();
        providerRepository.findAll().forEach(provider -> {
            if (provider.getMaxConcurrency() != null) {
                providerLimits.put(provider.getId(), provider.getMaxConcurrency());
            }
        });
        LinkedHashMap<String, Integer> modelLimits = new LinkedHashMap<>();
        LinkedHashMap<String, String> modelProviderIds = new LinkedHashMap<>();
        modelRepository.findAll().forEach(model -> {
            modelProviderIds.put(model.getId(), model.getProvider().getId());
            if (model.getMaxConcurrency() != null) {
                modelLimits.put(model.getId(), model.getMaxConcurrency());
            }
        });
        return new RuntimeConcurrencyPolicy(globalLimit, providerLimits, modelLimits, modelProviderIds);
    }

    /**
     * 查询当前用户可访问的供应商，供连通性检测读取已保存密钥。
     *
     * @param user 当前登录用户
     * @param providerId 供应商 ID
     * @return 供应商连接配置
     */
    @Transactional(readOnly = true)
    public Optional<RuntimeProviderConfig> visibleProvider(UserEntity user, String providerId) {
        String id = TextKit.blankToNull(providerId);
        if (user == null || id == null) {
            return Optional.empty();
        }
        return providerRepository.findByIdAndOwnerId(id, user.getId())
                .or(() -> providerRepository.findByIdAndOwnerIsNull(id))
                .map(this::toProviderConfig);
    }

    /**
     * 查询供应商下已配置的模型标识。
     *
     * @param user 当前登录用户
     * @param providerId 供应商 ID
     * @return 模型标识列表
     */
    @Transactional(readOnly = true)
    public List<String> configuredModels(UserEntity user, String providerId) {
        String id = TextKit.blankToNull(providerId);
        if (visibleProvider(user, id).isEmpty()) {
            return List.of();
        }
        return visibleModels(user).stream()
                .filter(model -> id.equals(model.getProviderId()))
                .map(RuntimeModelProfileConfig::getModel)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private void applyProvider(ModelProviderEntity entity, RuntimeProviderRequest request, boolean creating) {
        RuntimeProviderType type = requireProviderType(request.getProviderType());
        String name = requiredText(request.getName(), "请输入供应商名称");
        String baseUrl = requiredText(request.getBaseUrl(), "请输入服务地址");
        ensureUniqueProviderName(entity, name);
        String apiKey = TextKit.blankToNull(request.getApiKey());
        if (creating && apiKey == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "请输入 API Key");
        }
        if (apiKey != null) {
            entity.setApiKey(apiKey);
        }
        String instructionPrompt = TextKit.blankToNull(request.getInstructionPrompt());
        validatePrompt(instructionPrompt, "供应商公共提示词不能超过 10000 个字符");
        entity.setProviderType(type.value());
        entity.setName(name);
        entity.setBaseUrl(normalizeBaseUrl(baseUrl));
        entity.setInstructionPrompt(instructionPrompt);
        entity.setMaxConcurrency(normalizeConcurrency(request.getMaxConcurrency(), "供应商并行度必须在 1 到 20 之间"));
        entity.setEnabled(request.isEnabled());
        entity.setSortOrder(request.getSortOrder() == null ? nextProviderSortOrder(entity) : request.getSortOrder());
        entity.setUpdatedAt(LocalDateTime.now());
    }

    private void applyModel(ModelProfileEntity entity, ModelConfigScope scope, RuntimeModelProfileRequest request,
                            UserEntity user) {
        String name = requiredText(request.getName(), "请输入模型名称");
        ensureUniqueModelName(entity, name);
        ModelProviderEntity provider = requireProvider(scope,
                requiredText(request.getProviderId(), "请选择模型供应商"), user);
        RuntimeProviderType providerType = requireProviderType(provider.getProviderType());
        String model = requiredText(request.getModel(), "请输入模型标识");
        int contextWindow = request.getContextWindowTokens() == null
                ? DEFAULT_MODEL_CONTEXT_WINDOW : request.getContextWindowTokens();
        if (contextWindow < MIN_MODEL_CONTEXT_WINDOW || contextWindow > MAX_MODEL_CONTEXT_WINDOW) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                    "模型上下文窗口必须在 16K 到 10M Token 之间");
        }
        String instructionPrompt = TextKit.blankToNull(request.getInstructionPrompt());
        validatePrompt(instructionPrompt, "模型附加提示词不能超过 10000 个字符");
        RuntimeModelProtocol protocol = normalizeProtocol(request.getProtocol(), providerType, name);
        entity.setProvider(provider);
        entity.setName(name);
        entity.setDescription(TextKit.blankToNull(request.getDescription()));
        entity.setModel(model);
        entity.setProtocol(protocol);
        entity.setContextWindowTokens(contextWindow);
        entity.setReasoningEffort(normalizeReasoningEffort(request.getReasoningEffort(), providerType, name));
        entity.setInstructionPrompt(instructionPrompt);
        entity.setMaxConcurrency(normalizeConcurrency(request.getMaxConcurrency(), "模型并行度必须在 1 到 20 之间"));
        entity.setEnabled(request.isEnabled());
        entity.setSortOrder(request.getSortOrder() == null ? nextModelSortOrder(entity, scope, user) : request.getSortOrder());
        entity.setUpdatedAt(LocalDateTime.now());
    }

    private void applyPricingPlan(ModelPricingPlanEntity entity, ModelConfigScope scope,
                                  RuntimePricingPlanConfig request, UserEntity user) {
        ModelProviderEntity provider = requireProvider(scope,
                requiredText(request.getProviderId(), "请选择计价供应商"), user);
        ModelProfileEntity model = TextKit.blankToNull(request.getModelProfileId()) == null
                ? null : requireModel(scope, request.getModelProfileId(), user);
        if (model != null && !model.getProvider().getId().equals(provider.getId())) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                    "计价模型不属于所选供应商");
        }
        String name = requiredText(request.getName(), "请输入计价方案名称");
        ensureUniquePricingTarget(entity, provider, model);
        entity.setProvider(provider);
        entity.setModel(model);
        entity.setName(name);
        entity.setPricing(normalizePricing(request.getPricing(), name));
        entity.setSortOrder(request.getSortOrder() == null ? nextPricingSortOrder(provider.getId()) : request.getSortOrder());
        entity.setUpdatedAt(LocalDateTime.now());
    }

    private RuntimeModelProfileConfig resolveModel(ModelProfileEntity entity) {
        RuntimeProviderConfig provider = toProviderConfig(entity.getProvider());
        RuntimeProviderType type = requireProviderType(provider.getProviderType());
        RuntimeModelProfileConfig model = new RuntimeModelProfileConfig();
        model.setId(entity.getId());
        model.setName(entity.getName());
        model.setDescription(entity.getDescription());
        model.setProviderId(provider.getId());
        model.setProviderName(provider.getName());
        model.setProviderType(provider.getProviderType());
        model.setBaseUrl(provider.getBaseUrl());
        model.setApiKey(provider.getApiKey());
        model.setModel(entity.getModel());
        model.setProtocol(normalizeProtocol(entity.getProtocol(), type, entity.getName()));
        model.setContextWindowTokens(entity.getContextWindowTokens());
        model.setReasoningEffort(entity.getReasoningEffort());
        model.setThinkingFieldName(type.thinkingFieldName());
        model.setInstructionPrompt(mergeInstructions(provider.getInstructionPrompt(), entity.getInstructionPrompt()));
        model.setMaxConcurrency(entity.getMaxConcurrency());
        model.setImageInputSupported(type.imageInputSupported());
        model.setEnabled(entity.isEnabled() && provider.isEnabled());
        model.setSortOrder(entity.getSortOrder());
        return model;
    }

    private RuntimeProviderConfig toProviderConfig(ModelProviderEntity entity) {
        RuntimeProviderType type = requireProviderType(entity.getProviderType());
        RuntimeProviderConfig provider = new RuntimeProviderConfig();
        provider.setId(entity.getId());
        provider.setProviderType(type.value());
        provider.setName(entity.getName());
        provider.setBaseUrl(entity.getBaseUrl());
        provider.setApiKey(entity.getApiKey());
        provider.setInstructionPrompt(entity.getInstructionPrompt());
        provider.setReasoningEffortOptions(type.reasoningEffortOptions());
        provider.setMaxConcurrency(entity.getMaxConcurrency());
        provider.setEnabled(entity.isEnabled());
        provider.setSortOrder(entity.getSortOrder());
        return provider;
    }

    private RuntimeProviderResponse toProviderResponse(ModelProviderEntity entity) {
        RuntimeProviderConfig provider = toProviderConfig(entity);
        RuntimeProviderResponse response = new RuntimeProviderResponse();
        response.setId(provider.getId());
        response.setProviderType(provider.getProviderType());
        response.setName(provider.getName());
        response.setBaseUrl(provider.getBaseUrl());
        response.setApiKeyConfigured(TextKit.blankToNull(provider.getApiKey()) != null);
        response.setApiKeyMasked(SecretValueKit.mask(provider.getApiKey()));
        response.setInstructionPrompt(provider.getInstructionPrompt());
        response.setReasoningEffortOptions(provider.getReasoningEffortOptions());
        response.setMaxConcurrency(provider.getMaxConcurrency());
        response.setEnabled(provider.isEnabled());
        response.setSortOrder(provider.getSortOrder());
        return response;
    }

    private RuntimeModelProfileResponse toModelResponse(ModelProfileEntity entity) {
        RuntimeProviderType type = requireProviderType(entity.getProvider().getProviderType());
        RuntimeModelProfileResponse response = new RuntimeModelProfileResponse();
        response.setId(entity.getId());
        response.setName(entity.getName());
        response.setDescription(entity.getDescription());
        response.setProviderId(entity.getProvider().getId());
        response.setModel(entity.getModel());
        response.setProtocol(normalizeProtocol(entity.getProtocol(), type, entity.getName()));
        response.setContextWindowTokens(entity.getContextWindowTokens());
        response.setReasoningEffort(entity.getReasoningEffort());
        response.setInstructionPrompt(entity.getInstructionPrompt());
        response.setMaxConcurrency(entity.getMaxConcurrency());
        response.setImageInputSupported(type.imageInputSupported());
        response.setEnabled(entity.isEnabled());
        response.setSortOrder(entity.getSortOrder());
        return response;
    }

    private RuntimePricingPlanConfig toPricingPlan(ModelPricingPlanEntity entity) {
        RuntimePricingPlanConfig response = new RuntimePricingPlanConfig();
        response.setId(entity.getId());
        response.setName(entity.getName());
        response.setProviderId(entity.getProvider().getId());
        response.setModelProfileId(entity.getModel() == null ? null : entity.getModel().getId());
        response.setPricing(entity.getPricing());
        response.setSortOrder(entity.getSortOrder());
        return response;
    }

    private List<ModelProviderEntity> providers(ModelConfigScope scope, UserEntity user) {
        return scope == ModelConfigScope.PLATFORM
                ? providerRepository.findByOwnerIsNullOrderBySortOrderAscNameAsc()
                : providerRepository.findByOwnerIdOrderBySortOrderAscNameAsc(user.getId());
    }

    private List<ModelProfileEntity> models(ModelConfigScope scope, UserEntity user) {
        return scope == ModelConfigScope.PLATFORM
                ? modelRepository.findByOwnerIsNullOrderBySortOrderAscNameAsc()
                : modelRepository.findByOwnerIdOrderBySortOrderAscNameAsc(user.getId());
    }

    private List<ModelPricingPlanEntity> pricingPlans(ModelConfigScope scope, UserEntity user) {
        return scope == ModelConfigScope.PLATFORM
                ? pricingPlanRepository.findByProviderOwnerIsNullOrderBySortOrderAscNameAsc()
                : pricingPlanRepository.findByProviderOwnerIdOrderBySortOrderAscNameAsc(user.getId());
    }

    private ModelProviderEntity requireProvider(ModelConfigScope scope, String id, UserEntity user) {
        return (scope == ModelConfigScope.PLATFORM
                ? providerRepository.findByIdAndOwnerIsNull(id)
                : providerRepository.findByIdAndOwnerId(id, user.getId()))
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.VALIDATION_FAILED,
                        "模型供应商不存在"));
    }

    private ModelProfileEntity requireModel(ModelConfigScope scope, String id, UserEntity user) {
        return (scope == ModelConfigScope.PLATFORM
                ? modelRepository.findByIdAndOwnerIsNull(id)
                : modelRepository.findByIdAndOwnerId(id, user.getId()))
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.VALIDATION_FAILED,
                        "模型不存在"));
    }

    private ModelPricingPlanEntity requirePricingPlan(ModelConfigScope scope, String id, UserEntity user) {
        return (scope == ModelConfigScope.PLATFORM
                ? pricingPlanRepository.findByIdAndProviderOwnerIsNull(id)
                : pricingPlanRepository.findByIdAndProviderOwnerId(id, user.getId()))
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.VALIDATION_FAILED,
                        "计价方案不存在"));
    }

    private void ensureUniqueProviderName(ModelProviderEntity entity, String name) {
        boolean duplicated = entity.getOwner() == null
                ? providerRepository.findByOwnerIsNullOrderBySortOrderAscNameAsc().stream().anyMatch(item ->
                    !item.getId().equals(entity.getId()) && item.getName().equalsIgnoreCase(name))
                : providerRepository.findByOwnerIdOrderBySortOrderAscNameAsc(entity.getOwner().getId()).stream()
                    .anyMatch(item -> !item.getId().equals(entity.getId()) && item.getName().equalsIgnoreCase(name));
        if (duplicated) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "供应商名称不能重复");
        }
    }

    private void ensureUniqueModelName(ModelProfileEntity entity, String name) {
        boolean duplicated = entity.getOwner() == null
                ? modelRepository.findByOwnerIsNullOrderBySortOrderAscNameAsc().stream().anyMatch(item ->
                    !item.getId().equals(entity.getId()) && item.getName().equalsIgnoreCase(name))
                : modelRepository.findByOwnerIdOrderBySortOrderAscNameAsc(entity.getOwner().getId()).stream()
                    .anyMatch(item -> !item.getId().equals(entity.getId()) && item.getName().equalsIgnoreCase(name));
        if (duplicated) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "模型名称不能重复");
        }
    }

    private void ensureUniquePricingTarget(ModelPricingPlanEntity entity, ModelProviderEntity provider,
                                           ModelProfileEntity model) {
        boolean duplicated = pricingPlanRepository.findAll().stream()
                .filter(item -> item.getProvider().getId().equals(provider.getId()))
                .filter(item -> !item.getId().equals(entity.getId()))
                .anyMatch(item -> Objects.equals(
                        item.getModel() == null ? null : item.getModel().getId(),
                        model == null ? null : model.getId()));
        if (duplicated) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                    model == null ? "该供应商已经配置默认价格" : "该模型已经配置专属价格");
        }
    }

    private int nextProviderSortOrder(ModelProviderEntity entity) {
        return (entity.getOwner() == null
                ? providerRepository.findByOwnerIsNullOrderBySortOrderAscNameAsc()
                : providerRepository.findByOwnerIdOrderBySortOrderAscNameAsc(entity.getOwner().getId()))
                .stream().map(ModelProviderEntity::getSortOrder).filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(-10) + 10;
    }

    private int nextModelSortOrder(ModelProfileEntity entity, ModelConfigScope scope, UserEntity user) {
        return models(scope, user).stream().map(ModelProfileEntity::getSortOrder).filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(-10) + 10;
    }

    private int nextPricingSortOrder(String providerId) {
        return pricingPlanRepository.findAll().stream()
                .filter(item -> providerId.equals(item.getProvider().getId()))
                .map(ModelPricingPlanEntity::getSortOrder).filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(-10) + 10;
    }

    private RuntimeProviderType requireProviderType(String value) {
        String type = TextKit.blankToNull(value);
        if (type == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "请选择模型供应商类型");
        }
        return providerCatalog.find(type)
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                        "平台不支持该模型供应商类型：" + type));
    }

    private RuntimeModelProtocol normalizeProtocol(RuntimeModelProtocol requested, RuntimeProviderType providerType,
                                                    String modelName) {
        if (providerType.supportedProtocols().size() == 1) {
            return providerType.supportedProtocols().get(0);
        }
        RuntimeModelProtocol protocol = requested == null ? providerType.defaultProtocol() : requested;
        if (!providerType.supportedProtocols().contains(protocol)) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                    "当前供应商不支持模型协议：" + modelName);
        }
        return protocol;
    }

    private String normalizeReasoningEffort(String value, RuntimeProviderType providerType, String modelName) {
        String effort = TextKit.blankToNull(value);
        if (effort == null) {
            return null;
        }
        boolean supported = providerType.reasoningEffortOptions().stream()
                .map(RuntimeReasoningEffortOption::getValue).anyMatch(effort::equals);
        if (!supported) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                    "模型推理强度不在供应商可选范围内：" + modelName);
        }
        return effort;
    }

    private RuntimeModelPricing normalizePricing(RuntimeModelPricing pricing, String planName) {
        if (pricing == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                    "请配置计价信息：" + planName);
        }
        BigDecimal cacheHit = requiredRate(pricing.cacheHitInputPerMillion(), planName, "缓存命中输入");
        BigDecimal cacheMiss = requiredRate(pricing.cacheMissInputPerMillion(), planName, "缓存未命中输入");
        BigDecimal output = requiredRate(pricing.outputPerMillion(), planName, "输出");
        String currency = Optional.ofNullable(TextKit.blankToNull(pricing.currency())).orElse("CNY").toUpperCase(Locale.ROOT);
        String timeZone = Optional.ofNullable(TextKit.blankToNull(pricing.timeZone())).orElse("Asia/Shanghai");
        try {
            ZoneId.of(timeZone);
        } catch (RuntimeException exception) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                    "计价时区无效：" + planName);
        }
        List<RuntimePricingScheduleRule> rules = normalizeScheduleRules(pricing.scheduleRules(), planName);
        return new RuntimeModelPricing(currency, timeZone, cacheHit, cacheMiss, output, rules);
    }

    private List<RuntimePricingScheduleRule> normalizeScheduleRules(List<RuntimePricingScheduleRule> rules,
                                                                    String planName) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        boolean[] occupiedMinutes = new boolean[24 * 60];
        List<RuntimePricingScheduleRule> normalized = new ArrayList<>();
        for (RuntimePricingScheduleRule rule : rules) {
            if (rule == null) {
                throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                        "计价时段规则不能为空：" + planName);
            }
            String name = requiredText(rule.name(), "请输入计价时段名称");
            BigDecimal multiplier = rule.multiplier();
            if (multiplier == null || multiplier.signum() <= 0) {
                throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                        name + "的价格倍率必须大于 0");
            }
            List<RuntimePricingTimeRange> ranges = rule.timeRanges();
            if (ranges == null || ranges.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                        name + "至少需要一个生效时段");
            }
            List<RuntimePricingTimeRange> normalizedRanges = new ArrayList<>();
            for (RuntimePricingTimeRange range : ranges) {
                LocalTime start;
                LocalTime end;
                try {
                    start = LocalTime.parse(requiredText(range == null ? null : range.start(), "请选择开始时间"));
                    end = LocalTime.parse(requiredText(range == null ? null : range.end(), "请选择结束时间"));
                } catch (BizException exception) {
                    throw exception;
                } catch (RuntimeException exception) {
                    throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                            name + "的时段格式无效");
                }
                int startMinute = start.getHour() * 60 + start.getMinute();
                int endMinute = end.getHour() * 60 + end.getMinute();
                int minute = startMinute;
                do {
                    if (occupiedMinutes[minute]) {
                        throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                                "计价时段存在重叠：" + name);
                    }
                    occupiedMinutes[minute] = true;
                    minute = (minute + 1) % occupiedMinutes.length;
                } while (minute != endMinute);
                normalizedRanges.add(new RuntimePricingTimeRange(start.toString(), end.toString()));
            }
            normalized.add(new RuntimePricingScheduleRule(
                    name, multiplier.stripTrailingZeros(), List.copyOf(normalizedRanges)));
        }
        return List.copyOf(normalized);
    }

    private BigDecimal requiredRate(BigDecimal value, String planName, String rateName) {
        if (value == null || value.signum() < 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                    planName + " 的" + rateName + "单价必须大于或等于 0");
        }
        return value.stripTrailingZeros();
    }

    private String mergeInstructions(String providerInstruction, String modelInstruction) {
        String provider = TextKit.blankToNull(providerInstruction);
        String model = TextKit.blankToNull(modelInstruction);
        if (provider == null) {
            return model;
        }
        if (model == null || provider.equals(model)) {
            return provider;
        }
        return provider + System.lineSeparator() + System.lineSeparator() + model;
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            java.net.URI uri = java.net.URI.create(normalized);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "模型服务地址格式无效");
        }
        return normalized;
    }

    private Integer normalizeConcurrency(Integer value, String message) {
        if (value == null) {
            return null;
        }
        if (value < 1 || value > 20) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, message);
        }
        return value;
    }

    private void validatePrompt(String value, String message) {
        if (value != null && value.length() > MAX_INSTRUCTION_PROMPT_LENGTH) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, message);
        }
    }

    private UserEntity owner(ModelConfigScope scope, UserEntity user) {
        return scope == ModelConfigScope.PLATFORM ? null : requireUser(user);
    }

    private void authorizeWrite(ModelConfigScope scope, UserEntity user) {
        requireUser(user);
        if (scope == ModelConfigScope.PLATFORM && user.getRole() != UserRole.ADMIN) {
            throw new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.FORBIDDEN, "无权维护平台模型配置");
        }
    }

    private UserEntity requireUser(UserEntity user) {
        if (user == null) {
            throw new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.UNAUTHORIZED, "请先登录");
        }
        return user;
    }

    private String requiredText(String value, String message) {
        String normalized = TextKit.blankToNull(value);
        if (normalized == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, message);
        }
        return normalized;
    }

    private void changed() {
        eventPublisher.publishEvent(new RuntimeConfigChangedEvent());
    }
}
