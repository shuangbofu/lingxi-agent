package top.fusb.lingxi.runtime.config;

import top.fusb.lingxi.config.LingxiProperties;
import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.kit.TextKit;
import top.fusb.lingxi.runtime.api.model.RuntimeAvailability;
import top.fusb.lingxi.runtime.api.model.RuntimeDescriptor;
import top.fusb.lingxi.runtime.core.AgentRuntimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class RuntimeModeService {

    private final AgentRuntimeService agentRuntimeService;
    private final ModelCatalogService modelCatalogService;
    private final LingxiProperties lingxiProperties;
    private final RuntimeProviderCatalog runtimeProviderCatalog;

    /**
     * 查询当前可选择的执行模式。
     *
     * @param user 当前登录用户
     * @return 执行模式及当前用户可用的模型
     */
    @Transactional(readOnly = true)
    public List<RuntimeModeResponse> options(UserEntity user) {
        String defaultRuntimeCode = defaultCode();
        return agentRuntimeService.descriptors().stream()
                .map(descriptor -> toResponse(descriptor, defaultRuntimeCode, user))
                .toList();
    }

    /**
     * 读取并校验部署配置指定的默认运行时。
     *
     * @return 已注册的默认运行时编码
     * @throws IllegalArgumentException 默认运行时未注册时抛出
     */
    public String defaultCode() {
        String configured = TextKit.blankToNull(lingxiProperties.getRuntime().getDefaultCode());
        return configured == null ? agentRuntimeService.defaultCode() : agentRuntimeService.requireCode(configured);
    }

    /**
     * 校验并返回当前可用的运行时编码。
     *
     * @param runtimeCode 用户选择的运行时编码
     * @param user 当前登录用户
     * @return 标准化后的运行时编码
     * @throws BizException 编码为空、未注册或当前不可用时抛出
     */
    @Transactional(readOnly = true)
    public String requireAvailable(String runtimeCode, UserEntity user) {
        String code = TextKit.blankToNull(runtimeCode);
        if (code == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "请选择执行模式");
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        RuntimeModeResponse option;
        try {
            option = options(user).stream()
                    .filter(item -> item.getCode().equals(normalized))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("运行时未注册"));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.RUNTIME_UNAVAILABLE, "执行模式不可用：" + normalized);
        }
        if (!option.isAvailable()) {
            throw new BizException(ErrorCode.TASK_ERROR, ErrorSubCode.RUNTIME_UNAVAILABLE,
                    option.getUnavailableReason() == null ? "当前执行模式不可用" : option.getUnavailableReason());
        }
        return agentRuntimeService.requireCode(normalized);
    }

    /**
     * 校验运行时与模型配置组合，并返回模型配置。
     *
     * @param runtimeCode 运行时编码
     * @param modelProfileId 模型配置 ID
     * @param user 当前登录用户
     * @return 已启用且与运行时协议兼容的模型配置
     * @throws BizException 运行时、模型或凭证不可用时抛出
     */
    @Transactional(readOnly = true)
    public RuntimeModelProfileConfig requireAvailableModel(String runtimeCode, String modelProfileId, UserEntity user) {
        String normalizedRuntime = requireAvailable(runtimeCode, user);
        String profileId = TextKit.blankToNull(modelProfileId);
        if (profileId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "请选择模型");
        }
        RuntimeModeResponse runtime = options(user).stream()
                .filter(item -> normalizedRuntime.equals(item.getCode()))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.RUNTIME_UNAVAILABLE));
        RuntimeModelOptionResponse option = runtime.getModels().stream()
                .filter(item -> profileId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                        "请选择当前执行模式支持的模型"));
        if (!option.isAvailable()) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.RUNTIME_UNAVAILABLE,
                    option.getUnavailableReason() == null ? "所选模型不可用" : option.getUnavailableReason());
        }
        return modelCatalogService.visibleModel(user, option.getId())
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                        "所选模型不存在"));
    }

    private RuntimeModeResponse toResponse(RuntimeDescriptor descriptor, String defaultRuntimeCode, UserEntity user) {
        RuntimeAvailability runtimeAvailability = agentRuntimeService.availability(descriptor.code());
        List<RuntimeModelOptionResponse> models = modelCatalogService.visibleModels(user).stream()
                .filter(RuntimeModelProfileConfig::isEnabled)
                .filter(profile -> descriptor.supportedModelProtocols().contains(profile.getProtocol()))
                .map(profile -> modelOption(user, profile))
                .toList();
        String unavailableReason = runtimeAvailability.available()
                ? null
                : runtimeAvailability.unavailableReason();
        RuntimeModeResponse response = new RuntimeModeResponse();
        response.setCode(descriptor.code());
        response.setName(descriptor.name());
        response.setDescription(descriptor.description());
        response.setIconUrl(descriptor.iconUrl());
        response.setMessageStreamingSupported(descriptor.messageStreamingSupported());
        response.setMaintenanceSupported(descriptor.maintenanceSupported());
        response.setMcpSupported(descriptor.mcpSupported());
        response.setAvailable(unavailableReason == null);
        response.setUnavailableReason(unavailableReason);
        response.setDefaultSelected(java.util.Objects.equals(defaultRuntimeCode, descriptor.code()));
        response.setModels(models);
        response.setModelProviderTypes(runtimeProviderCatalog.definitions().stream()
                .filter(type -> type.supportedProtocols().stream().anyMatch(descriptor.supportedModelProtocols()::contains))
                .map(RuntimeProviderTypeOption::from)
                .toList());
        response.setSupportedModelProtocols(descriptor.supportedModelProtocols());
        response.setMaintenancePresentation(descriptor.maintenancePresentation());
        response.setModelTimingNote(descriptor.modelTimingNote());
        return response;
    }

    private RuntimeModelOptionResponse modelOption(UserEntity user, RuntimeModelProfileConfig profile) {
        String unavailableReason = null;
        if (TextKit.blankToNull(profile.getBaseUrl()) == null) {
            unavailableReason = "模型服务地址未配置";
        } else if (TextKit.blankToNull(profile.getModel()) == null) {
            unavailableReason = "模型标识未配置";
        } else if (TextKit.blankToNull(profile.getApiKey()) == null) {
            unavailableReason = "模型 API Key 未配置";
        }
        RuntimeProviderConfig provider = modelCatalogService.visibleProvider(user, profile.getProviderId())
                .orElse(null);
        RuntimeProviderType providerType = provider == null
                ? null
                : runtimeProviderCatalog.find(provider.getProviderType())
                        .orElseThrow(() -> new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                                "模型供应商类型无效：" + provider.getProviderType()));
        RuntimeModelOptionResponse response = new RuntimeModelOptionResponse();
        response.setId(profile.getId());
        response.setName(profile.getName());
        response.setDescription(profile.getDescription());
        response.setProviderId(profile.getProviderId());
        response.setProviderName(provider == null ? null : provider.getName());
        response.setProviderType(providerType == null ? null : providerType.value());
        response.setProviderTypeName(providerType == null ? null : providerType.label());
        response.setProviderIcon(providerType == null ? null : providerType.icon());
        response.setProviderDarkIcon(providerType == null ? null : providerType.darkIcon());
        response.setModel(profile.getModel());
        response.setProtocol(profile.getProtocol());
        response.setContextWindowTokens(profile.getContextWindowTokens());
        response.setReasoningEffort(profile.getReasoningEffort());
        response.setReasoningEffortLabel(java.util.stream.Stream.ofNullable(provider)
                .flatMap(item -> item.getReasoningEffortOptions() == null
                        ? java.util.stream.Stream.empty()
                        : item.getReasoningEffortOptions().stream())
                .filter(option -> profile.getReasoningEffort() != null
                        && profile.getReasoningEffort().equals(option.getValue()))
                .map(RuntimeReasoningEffortOption::getLabel)
                .findFirst()
                .orElse(null));
        response.setImageInputSupported(profile.isImageInputSupported());
        response.setPersonal(modelCatalogService.platformModel(profile.getId()).isEmpty());
        response.setAvailable(unavailableReason == null);
        response.setUnavailableReason(unavailableReason);
        return response;
    }
}
