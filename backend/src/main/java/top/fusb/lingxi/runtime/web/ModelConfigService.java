package top.fusb.lingxi.runtime.web;

import top.fusb.lingxi.dto.ModelEndpointCheckRequest;
import top.fusb.lingxi.dto.ModelEndpointCheckResponse;
import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.kit.TextKit;
import top.fusb.lingxi.runtime.config.ModelCatalogService;
import top.fusb.lingxi.runtime.config.RuntimeModelProfileConfig;
import top.fusb.lingxi.runtime.config.RuntimeProviderConfig;
import top.fusb.lingxi.runtime.model.ModelEndpointService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelConfigService {

    private final ModelCatalogService modelCatalogService;
    private final ModelEndpointService modelEndpointService;

    /**
     * 使用页面输入或当前用户可访问的已保存供应商检测模型服务。
     *
     * @param request 当前待检测的模型服务配置
     * @param user 当前登录用户
     * @return 模型服务连通状态和可选模型列表
     */
    public ModelEndpointCheckResponse check(ModelEndpointCheckRequest request, UserEntity user) {
        RuntimeModelProfileConfig profile = modelCatalogService.visibleModel(user, request.getModelProfileId())
                .orElse(null);
        RuntimeProviderConfig provider = modelCatalogService.visibleProvider(user, request.getProviderId())
                .orElse(null);
        String providerId = provider == null && profile != null ? profile.getProviderId() : request.getProviderId();
        List<String> configuredModels = providerId == null
                ? List.of()
                : modelCatalogService.configuredModels(user, providerId);
        String apiKey = firstConfigured(request.getApiKey(),
                provider != null ? provider.getApiKey() : profile == null ? null : profile.getApiKey());
        String baseUrl = firstConfigured(request.getBaseUrl(),
                provider != null ? provider.getBaseUrl() : profile == null ? null : profile.getBaseUrl());
        log.info("检测模型服务开始 ownerId={} baseUrlConfigured={} modelCount={} apiKeyConfigured={}",
                user.getId(), baseUrl != null, configuredModels.size(), apiKey != null);
        ModelEndpointCheckResponse response = modelEndpointService.check(apiKey, baseUrl, configuredModels);
        log.info("检测模型服务完成 ownerId={} success={} modelCount={}",
                user.getId(), response.isSuccess(), response.getModels().size());
        return response;
    }

    private String firstConfigured(String requestValue, String savedValue) {
        String value = TextKit.blankToNull(requestValue);
        return value == null ? TextKit.blankToNull(savedValue) : value;
    }
}
