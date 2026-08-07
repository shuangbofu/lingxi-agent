package top.fusb.lingxi.service;

import top.fusb.lingxi.dto.AgentDefinitionParameterResponse;
import top.fusb.lingxi.dto.AgentCapabilityResponse;
import top.fusb.lingxi.dto.AgentScenarioResponse;
import top.fusb.lingxi.dto.AnalysisPremiseContextParameterResponse;
import top.fusb.lingxi.dto.AnalysisPremiseOptionResponse;
import top.fusb.lingxi.dto.AnalysisPremiseResponse;
import top.fusb.lingxi.dto.AnalysisPremiseSaveRequest;
import top.fusb.lingxi.dto.PageResult;
import top.fusb.lingxi.dto.TaskCreateRequest;
import top.fusb.lingxi.dto.TaskInputValue;
import top.fusb.lingxi.entity.AnalysisPremiseEntity;
import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.enums.UserRole;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.kit.TextKit;
import top.fusb.lingxi.repository.AnalysisPremiseRepository;
import top.fusb.lingxi.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisPremiseService {

    private final AnalysisPremiseRepository analysisPremiseRepository;
    private final UserRepository userRepository;
    private final AgentScenarioService agentScenarioService;
    private final AgentCapabilityService agentCapabilityService;

    @Transactional(readOnly = true)
    public PageResult<AnalysisPremiseResponse> page(int page, int size, String query) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);
        PageRequest pageRequest = PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.ASC, "sortOrder").and(Sort.by(Sort.Direction.DESC, "updatedAt")));
        Page<AnalysisPremiseEntity> result = analysisPremiseRepository.findAll(premiseSpecification(query), pageRequest);
        return new PageResult<>(result.getContent().stream().map(this::toResponse).toList(), result.getTotalElements(), safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public List<AnalysisPremiseOptionResponse> availableOptions(UserEntity user) {
        List<AnalysisPremiseEntity> premises = availablePremises(user);
        return premises.stream().map(this::toOptionResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<AnalysisPremiseContextParameterResponse> contextParameters() {
        List<AgentCapabilityResponse> capabilities = agentCapabilityService.listAll();
        Map<String, AgentCapabilityResponse> capabilitiesByCode = capabilities.stream()
                .collect(java.util.stream.Collectors.toMap(AgentCapabilityResponse::getCode, item -> item, (left, right) -> left, LinkedHashMap::new));
        LinkedHashMap<String, AnalysisPremiseContextParameterResponse> result = new LinkedHashMap<>();
        for (AgentScenarioResponse scenario : agentScenarioService.listAll()) {
            for (AgentDefinitionParameterResponse parameter : scenario.getParameters()) {
                putParameter(result, scenario, "SCENARIO", scenario.getCode(), scenario.getName(), parameter);
            }
            for (String capabilityCode : scenario.getCapabilities()) {
                AgentCapabilityResponse capability = capabilitiesByCode.get(capabilityCode);
                if (capability == null) {
                    continue;
                }
                for (AgentDefinitionParameterResponse parameter : capability.getParameters()) {
                    putParameter(result, scenario, "CAPABILITY", capability.getCode(), capability.getName(), parameter);
                }
            }
        }
        return result.values().stream().toList();
    }

    @Transactional
    public AnalysisPremiseResponse create(AnalysisPremiseSaveRequest request) {
        AnalysisPremiseEntity entity = new AnalysisPremiseEntity();
        entity.setCreatedAt(LocalDateTime.now());
        apply(entity, request);
        AnalysisPremiseEntity saved = analysisPremiseRepository.save(entity);
        log.info("创建分析情境 id={} name={} globalVisible={}", saved.getId(), saved.getName(), saved.isGlobalVisible());
        return toResponse(saved);
    }

    @Transactional
    public AnalysisPremiseResponse update(Long id, AnalysisPremiseSaveRequest request) {
        AnalysisPremiseEntity entity = requireEntity(id);
        apply(entity, request);
        AnalysisPremiseEntity saved = analysisPremiseRepository.save(entity);
        log.info("更新分析情境 id={} name={} enabled={}", saved.getId(), saved.getName(), saved.isEnabled());
        return toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        AnalysisPremiseEntity entity = requireEntity(id);
        analysisPremiseRepository.delete(entity);
        log.info("删除分析情境 id={}", id);
    }

    @Transactional(readOnly = true)
    public AnalysisPremiseEntity resolveForTask(TaskCreateRequest request, UserEntity owner) {
        List<AnalysisPremiseEntity> premises = availablePremises(owner);
        if (request.getPremiseId() != null) {
            return premises.stream()
                    .filter(item -> item.getId().equals(request.getPremiseId()))
                    .findFirst()
                    .orElseThrow(() -> new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.FORBIDDEN, "没有使用该分析情境的权限"));
        }
        return premises.size() == 1 ? premises.get(0) : null;
    }

    @Transactional(readOnly = true)
    public Set<String> visibleScenarioCodes(Long premiseId, UserEntity user) {
        AnalysisPremiseEntity premise = resolveAccessiblePremise(premiseId, user);
        if (premise == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(premise.getVisibleScenarioCodes());
    }

    public void applyPremise(TaskCreateRequest request, AnalysisPremiseEntity premise, String scenarioCode) {
        if (premise == null) {
            return;
        }
        Map<String, String> contextValues = effectiveContextValues(premise, scenarioCode);
        if (contextValues.isEmpty()) {
            return;
        }
        List<TaskInputValue> inputValues = new ArrayList<>(request.getInputValues() == null ? List.of() : request.getInputValues());
        Set<String> existingKeys = new LinkedHashSet<>();
        inputValues.stream()
                .map(TaskInputValue::getKey)
                .filter(key -> key != null && !key.isBlank())
                .forEach(existingKeys::add);
        contextValues.forEach((key, value) -> {
            if (existingKeys.contains(key)) {
                return;
            }
            TaskInputValue inputValue = new TaskInputValue();
            inputValue.setKey(key);
            inputValue.setValue(value);
            inputValues.add(inputValue);
        });
        request.setInputValues(inputValues);
    }

    @Transactional
    public void seedBuiltin(String code, String name, String description, Map<String, String> contextValues,
                            String promptText, int sortOrder) {
        if (analysisPremiseRepository.findByCode(code).isPresent()) {
            return;
        }
        AnalysisPremiseEntity entity = new AnalysisPremiseEntity();
        entity.setCode(code);
        entity.setName(name);
        entity.setDescription(TextKit.blankToNull(description));
        entity.setContextValues(normalizeContextValues(contextValues));
        entity.setScenarioContextValues(new LinkedHashMap<>());
        entity.setVisibleScenarioCodes(new LinkedHashSet<>());
        entity.setPromptText(TextKit.blankToNull(promptText));
        entity.setEnabled(true);
        entity.setGlobalVisible(true);
        entity.setSortOrder(sortOrder);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(entity.getCreatedAt());
        AnalysisPremiseEntity saved = analysisPremiseRepository.save(entity);
        log.info("初始化内置分析情境 code={} id={} name={}", code, saved.getId(), saved.getName());
    }

    public String premisePromptText(AnalysisPremiseEntity premise) {
        return premisePromptText(premise, null);
    }

    public String premisePromptText(AnalysisPremiseEntity premise, String scenarioCode) {
        if (premise == null) {
            return "None.";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("contextName: ").append(premise.getName()).append(System.lineSeparator());
        if (TextKit.blankToNull(premise.getDescription()) != null) {
            builder.append("description: ").append(premise.getDescription().trim()).append(System.lineSeparator());
        }
        Map<String, String> contextValues = effectiveContextValues(premise, scenarioCode);
        if (!contextValues.isEmpty()) {
            builder.append("contextValues:").append(System.lineSeparator());
            contextValues.forEach((key, value) -> builder.append("- ").append(key).append(": ").append(value).append(System.lineSeparator()));
        }
        if (TextKit.blankToNull(premise.getPromptText()) != null) {
            builder.append("contextPrompt:").append(System.lineSeparator()).append(premise.getPromptText().trim()).append(System.lineSeparator());
        }
        builder.append("Rule: Treat explicit contextValues as user-provided task context. The context name, description and contextPrompt are guidance only, not structured external resource values. Do not choose a concrete external resource from context wording alone. Candidate count alone does not require user interaction: use the current input, structured context and capability evidence to select a clear match, and ask only when multiple reasonable choices would materially change the task result.");
        return builder.toString();
    }

    private Specification<AnalysisPremiseEntity> premiseSpecification(String query) {
        return (root, criteriaQuery, criteriaBuilder) -> {
            String keyword = TextKit.blankToNull(query);
            if (keyword == null) {
                return criteriaBuilder.conjunction();
            }
            String pattern = "%" + keyword.toLowerCase() + "%";
            return criteriaBuilder.or(
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("name")), pattern),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("description")), pattern)
            );
        };
    }

    private List<AnalysisPremiseEntity> availablePremises(UserEntity user) {
        if (user == null) {
            return List.of();
        }
        List<AnalysisPremiseEntity> all = analysisPremiseRepository.findAllByEnabledTrueOrderBySortOrderAscUpdatedAtDesc();
        if (user.getRole() == UserRole.ADMIN) {
            return all;
        }
        return all.stream()
                .filter(item -> item.isGlobalVisible() || item.getAssignedUserIds().contains(user.getId()))
                .sorted(Comparator.comparing(AnalysisPremiseEntity::getSortOrder).thenComparing(AnalysisPremiseEntity::getUpdatedAt, Comparator.reverseOrder()))
                .toList();
    }

    private void apply(AnalysisPremiseEntity entity, AnalysisPremiseSaveRequest request) {
        entity.setName(request.getName().trim());
        entity.setDescription(TextKit.blankToNull(request.getDescription()));
        entity.setPromptText(TextKit.blankToNull(request.getPromptText()));
        entity.setContextValues(normalizeContextValues(request.getContextValues()));
        entity.setScenarioContextValues(normalizeScenarioContextValues(request.getScenarioContextValues()));
        entity.setVisibleScenarioCodes(validateScenarioCodes(request.getVisibleScenarioCodes()));
        entity.setEnabled(request.getEnabled() == null || request.getEnabled());
        entity.setGlobalVisible(Boolean.TRUE.equals(request.getGlobalVisible()));
        entity.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
        entity.setAssignedUserIds(entity.isGlobalVisible() ? new LinkedHashSet<>() : validateUserIds(request.getAssignedUserIds()));
        entity.setUpdatedAt(LocalDateTime.now());
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(entity.getUpdatedAt());
        }
    }

    private Set<Long> validateUserIds(Set<Long> userIds) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        if (userIds == null || userIds.isEmpty()) {
            return result;
        }
        userIds.stream().filter(id -> id != null && id > 0).forEach(result::add);
        if (result.isEmpty()) {
            return result;
        }
        Set<Long> exists = new LinkedHashSet<>(userRepository.findAllById(result).stream().map(UserEntity::getId).toList());
        if (exists.size() != result.size()) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "分配用户不存在");
        }
        return result;
    }

    private Set<String> validateScenarioCodes(Set<String> scenarioCodes) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (scenarioCodes == null || scenarioCodes.isEmpty()) {
            return result;
        }
        scenarioCodes.stream().map(TextKit::blankToNull).filter(java.util.Objects::nonNull)
                .map(String::trim).forEach(result::add);
        if (result.isEmpty()) {
            return result;
        }
        Set<String> exists = new LinkedHashSet<>(agentScenarioService.findAllByCodes(result).stream()
                .map(top.fusb.lingxi.entity.AgentScenarioEntity::getCode).toList());
        if (exists.size() != result.size()) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "可见场景不存在");
        }
        return result;
    }

    private Map<String, String> normalizeContextValues(Map<String, String> contextValues) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (contextValues == null) {
            return result;
        }
        contextValues.forEach((key, value) -> {
            String normalizedKey = TextKit.blankToNull(key);
            String normalizedValue = TextKit.blankToNull(value);
            if (normalizedKey != null && normalizedValue != null) {
                result.put(normalizedKey.trim(), normalizedValue.trim());
            }
        });
        return result;
    }

    private Map<String, Map<String, String>> normalizeScenarioContextValues(Map<String, Map<String, String>> scenarioContextValues) {
        LinkedHashMap<String, Map<String, String>> result = new LinkedHashMap<>();
        if (scenarioContextValues == null) {
            return result;
        }
        scenarioContextValues.forEach((scenarioCode, values) -> {
            String normalizedScenario = TextKit.blankToNull(scenarioCode);
            Map<String, String> normalizedValues = normalizeContextValues(values);
            if (normalizedScenario != null && !normalizedValues.isEmpty()) {
                result.put(normalizedScenario.trim(), normalizedValues);
            }
        });
        return result;
    }

    public Map<String, String> effectiveContextValues(AnalysisPremiseEntity premise, String scenarioCode) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        result.putAll(normalizeContextValues(premise.getContextValues()));
        String normalizedScenario = TextKit.blankToNull(scenarioCode);
        if (normalizedScenario != null) {
            Map<String, String> scenarioValues = normalizeScenarioContextValues(premise.getScenarioContextValues()).get(normalizedScenario);
            if (scenarioValues != null) {
                result.putAll(scenarioValues);
            }
        }
        return result;
    }

    private void putParameter(LinkedHashMap<String, AnalysisPremiseContextParameterResponse> result,
                              AgentScenarioResponse scenario,
                              String sourceType,
                              String sourceCode,
                              String sourceName,
                              AgentDefinitionParameterResponse parameter) {
        if (parameter == null || TextKit.blankToNull(parameter.getKey()) == null || "userInput".equals(parameter.getKey())) {
            return;
        }
        String mapKey = scenario.getCode() + ":" + sourceType + ":" + sourceCode + ":" + parameter.getKey();
        AnalysisPremiseContextParameterResponse response = new AnalysisPremiseContextParameterResponse();
        response.setKey(parameter.getKey());
        response.setName(parameter.getName());
        response.setType(parameter.getType());
        response.setDescription(parameter.getDescription());
        response.setOptions(parameter.getOptions());
        response.setDefaultValue(parameter.getDefaultValue());
        response.setSourceType(sourceType);
        response.setSourceCode(sourceCode);
        response.setSourceName(sourceName);
        response.setScenarioCode(scenario.getCode());
        response.setScenarioName(scenario.getName());
        response.setSortOrder(parameter.getSortOrder());
        result.putIfAbsent(mapKey, response);
    }

    private AnalysisPremiseEntity requireEntity(Long id) {
        return analysisPremiseRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.VALIDATION_FAILED, "分析情境不存在"));
    }

    private AnalysisPremiseEntity resolveAccessiblePremise(Long premiseId, UserEntity user) {
        List<AnalysisPremiseEntity> premises = availablePremises(user);
        if (premiseId != null) {
            return premises.stream()
                    .filter(item -> item.getId().equals(premiseId))
                    .findFirst()
                    .orElseThrow(() -> new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.FORBIDDEN, "没有使用该分析情境的权限"));
        }
        return premises.size() == 1 ? premises.get(0) : null;
    }

    private AnalysisPremiseOptionResponse toOptionResponse(AnalysisPremiseEntity entity) {
        AnalysisPremiseOptionResponse response = new AnalysisPremiseOptionResponse();
        response.setId(entity.getId());
        response.setName(entity.getName());
        response.setDescription(entity.getDescription());
        return response;
    }

    private AnalysisPremiseResponse toResponse(AnalysisPremiseEntity entity) {
        AnalysisPremiseResponse response = new AnalysisPremiseResponse();
        response.setId(entity.getId());
        response.setCode(entity.getCode());
        response.setName(entity.getName());
        response.setDescription(entity.getDescription());
        response.setPromptText(entity.getPromptText());
        response.setContextValues(normalizeContextValues(entity.getContextValues()));
        response.setScenarioContextValues(normalizeScenarioContextValues(entity.getScenarioContextValues()));
        response.setVisibleScenarioCodes(entity.getVisibleScenarioCodes() == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(entity.getVisibleScenarioCodes()));
        response.setEnabled(entity.isEnabled());
        response.setGlobalVisible(entity.isGlobalVisible());
        response.setSortOrder(entity.getSortOrder());
        response.setAssignedUserIds(copyLongSet(entity.getAssignedUserIds()));
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        return response;
    }

    private Set<Long> copyLongSet(Set<Long> values) {
        return values == null ? new LinkedHashSet<>() : new LinkedHashSet<>(values);
    }
}
