package top.fusb.lingxi.service;

import top.fusb.lingxi.dto.CapabilityConfigResponse;
import top.fusb.lingxi.dto.CapabilityConfigSaveRequest;
import top.fusb.lingxi.dto.PageResult;
import top.fusb.lingxi.definition.ModuleDefinition;
import top.fusb.lingxi.definition.ModuleParameterDefinition;
import top.fusb.lingxi.entity.CapabilityConfigEntity;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.kit.TextKit;
import top.fusb.lingxi.kit.SensitiveTextKit;
import top.fusb.lingxi.repository.CapabilityConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class CapabilityConfigService {

    private final CapabilityConfigRepository capabilityConfigRepository;
    private final AgentCapabilityService agentCapabilityService;

    @Transactional(readOnly = true)
    public List<CapabilityConfigEntity> listEnabledConfigs() {
        return capabilityConfigRepository.findAllByOrderByUpdatedAtDesc().stream()
                .filter(CapabilityConfigEntity::isEnabled)
                .toList();
    }

    /**
     * 读取指定能力最近更新的一份启用配置，供定义模块的动态选项源使用。
     *
     * @param capabilityCode 能力编码
     * @return 与持久化实体隔离的配置数据
     * @throws BizException 能力没有启用的服务接入配置时抛出
     */
    @Transactional(readOnly = true)
    public Map<String, Object> enabledConfigData(String capabilityCode) {
        return capabilityConfigRepository.findAllByEnabledTrueOrderByUpdatedAtDesc().stream()
                .filter(config -> capabilityCode.equals(config.getCapabilityCode()))
                .findFirst()
                .map(config -> new LinkedHashMap<>(config.getConfig()))
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.VALIDATION_FAILED,
                        "动态选项源缺少启用的服务接入配置：" + capabilityCode));
    }

    /**
     * 在配置事务内提取指定能力的敏感值，供所有 Agent Runtime 统一做输出脱敏。
     *
     * @param capabilityCodes 当前任务启用的能力编码
     * @return 去重后的敏感配置明文；仅用于内存中的输出脱敏
     */
    @Transactional(readOnly = true)
    public List<String> sensitiveValues(Set<String> capabilityCodes) {
        Set<String> allowedCodes = capabilityCodes == null ? Set.of() : capabilityCodes;
        return capabilityConfigRepository.findAllByOrderByUpdatedAtDesc().stream()
                .filter(CapabilityConfigEntity::isEnabled)
                .filter(config -> allowedCodes.contains(config.getCapabilityCode()))
                .flatMap(config -> SensitiveTextKit.sensitiveValues(config.getConfig()).stream())
                .distinct()
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResult<CapabilityConfigResponse> page(int page, int size, String capabilityCode) {
        PageRequest pageRequest = PageRequest.of(Math.max(page - 1, 0), size);
        Page<CapabilityConfigEntity> result = TextKit.blankToNull(capabilityCode) == null
                ? capabilityConfigRepository.findAllByOrderByUpdatedAtDesc(pageRequest)
                : capabilityConfigRepository.findByCapabilityCodeOrderByUpdatedAtDesc(capabilityCode.trim(), pageRequest);
        return new PageResult<>(result.getContent().stream().map(this::toResponse).toList(), result.getTotalElements(), page, size);
    }

    @Transactional
    public CapabilityConfigResponse create(CapabilityConfigSaveRequest request) {
        CapabilityConfigEntity entity = new CapabilityConfigEntity();
        entity.setCreatedAt(LocalDateTime.now());
        apply(entity, request);
        CapabilityConfigEntity saved = capabilityConfigRepository.save(entity);
        log.info("create capability config id={} name={} capabilityCode={}", saved.getId(), saved.getName(), saved.getCapabilityCode());
        return toResponse(saved);
    }

    @Transactional
    public CapabilityConfigResponse update(Long id, CapabilityConfigSaveRequest request) {
        CapabilityConfigEntity entity = requireEntity(id);
        apply(entity, request);
        CapabilityConfigEntity saved = capabilityConfigRepository.save(entity);
        log.info("update capability config id={} name={} capabilityCode={}", saved.getId(), saved.getName(), saved.getCapabilityCode());
        return toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        CapabilityConfigEntity entity = requireEntity(id);
        capabilityConfigRepository.delete(entity);
        log.info("delete capability config id={}", id);
    }

    public CapabilityConfigEntity requireEnabledConfig(Long capabilityConfigId) {
        CapabilityConfigEntity config = requireEntity(capabilityConfigId);
        if (!config.isEnabled()) {
            throw new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.VALIDATION_FAILED, "服务接入配置未启用");
        }
        return config;
    }

    private CapabilityConfigEntity requireEntity(Long id) {
        return capabilityConfigRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.VALIDATION_FAILED, "服务接入配置不存在"));
    }

    private void apply(CapabilityConfigEntity entity, CapabilityConfigSaveRequest request) {
        ModuleDefinition capability = agentCapabilityService.requireDefinition(request.getCapabilityCode());
        if (capability.getConfig() == null || capability.getConfig().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "该能力没有服务接入参数，不需要创建服务接入配置");
        }
        entity.setName(request.getName().trim());
        entity.setCapabilityCode(capability.getCode());
        entity.setDescription(TextKit.blankToNull(request.getDescription()));
        entity.setConfig(normalizeConfig(capability, request.getConfig()));
        entity.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        entity.setUpdatedAt(LocalDateTime.now());
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(entity.getUpdatedAt());
        }
    }

    private CapabilityConfigResponse toResponse(CapabilityConfigEntity entity) {
        CapabilityConfigResponse response = new CapabilityConfigResponse();
        response.setId(entity.getId());
        response.setName(entity.getName());
        response.setCapabilityCode(entity.getCapabilityCode());
        response.setCapabilityName(agentCapabilityService.requireDefinition(entity.getCapabilityCode()).getName());
        response.setDescription(entity.getDescription());
        response.setConfig(entity.getConfig());
        response.setEnabled(entity.isEnabled());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        return response;
    }

    private Map<String, Object> normalizeConfig(ModuleDefinition capability, Map<String, Object> requestConfig) {
        Map<String, Object> source = requestConfig == null ? Map.of() : requestConfig;
        Map<String, Object> result = new LinkedHashMap<>();
        for (ModuleParameterDefinition parameter : capability.getConfig()) {
            Object value = source.get(parameter.getKey());
            if (!hasParameterValue(value) && TextKit.blankToNull(parameter.getDefaultValue()) != null) {
                value = parameter.getDefaultValue();
            }
            if (Boolean.TRUE.equals(parameter.getRequired()) && !hasParameterValue(value)) {
                throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "服务接入配置缺少必填参数：" + parameter.getName());
            }
            if (hasParameterValue(value)) {
                result.put(parameter.getKey(), value);
            }
        }
        return result;
    }

    private boolean hasParameterValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return TextKit.blankToNull(text) != null;
        }
        return true;
    }
}
