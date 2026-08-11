package top.fusb.lingxi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.fusb.lingxi.definition.CapabilityActivationCondition;
import top.fusb.lingxi.definition.ModuleDefinition;
import top.fusb.lingxi.definition.ModuleGuideDefinition;
import top.fusb.lingxi.definition.ModuleParameterDefinition;
import top.fusb.lingxi.dto.AgentScenarioResponse;
import top.fusb.lingxi.dto.AgentScenarioStateUpdateRequest;
import top.fusb.lingxi.dto.AgentTaskDefinition;
import top.fusb.lingxi.dto.PageResult;
import top.fusb.lingxi.dto.PublicScenarioResponse;
import top.fusb.lingxi.entity.AgentScenarioEntity;
import top.fusb.lingxi.entity.AnalysisPremiseEntity;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.enums.ScenarioInputMode;
import top.fusb.lingxi.enums.TaskStatus;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.kit.DefinitionAssetUrlKit;
import top.fusb.lingxi.repository.AgentScenarioRepository;
import top.fusb.lingxi.repository.AgentTaskRepository;
import top.fusb.lingxi.repository.AnalysisPremiseRepository;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentScenarioService {

    private final AgentScenarioRepository agentScenarioRepository;
    private final AgentTaskRepository agentTaskRepository;
    private final AnalysisPremiseRepository analysisPremiseRepository;
    private final ModuleDefinitionService moduleDefinitionService;
    private final AgentDefinitionSupport definitionSupport;
    private final DefinitionParameterOptionService definitionParameterOptionService;

    @Transactional(readOnly = true)
    public List<AgentScenarioResponse> listAll() {
        Map<String, ModuleDefinition> definitions = definitionMap();
        return agentScenarioRepository.findAllByOrderBySortOrderAsc().stream()
                .filter(state -> definitions.containsKey(state.getCode()))
                .map(state -> toResponse(definitions.get(state.getCode()), state))
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResult<AgentScenarioResponse> pageAll(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);
        List<AgentScenarioResponse> values = listAll();
        int from = Math.min((safePage - 1) * safeSize, values.size());
        int to = Math.min(from + safeSize, values.size());
        return new PageResult<>(values.subList(from, to), values.size(), safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public List<AgentScenarioResponse> listEnabled(Set<String> visibleScenarioCodes) {
        Set<String> visibleCodes = visibleScenarioCodes == null ? Set.of() : visibleScenarioCodes;
        Map<String, ModuleDefinition> definitions = definitionMap();
        return agentScenarioRepository.findAllByEnabledTrueOrderBySortOrderAsc().stream()
                .filter(AgentScenarioEntity::isUserVisible)
                .filter(state -> visibleCodes.isEmpty() || visibleCodes.contains(state.getCode()))
                .filter(state -> definitions.containsKey(state.getCode()))
                .map(state -> toResponse(definitions.get(state.getCode()), state))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PublicScenarioResponse> listPublicPreviews() {
        Map<String, ModuleDefinition> definitions = definitionMap();
        return agentScenarioRepository.findAllByEnabledTrueAndUserVisibleTrueOrderBySortOrderAsc().stream()
                .filter(state -> definitions.containsKey(state.getCode()))
                .map(state -> toPublicResponse(definitions.get(state.getCode()), state))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AgentScenarioEntity> findAllByCodes(Set<String> codes) {
        return codes == null || codes.isEmpty() ? List.of() : agentScenarioRepository.findAllById(codes);
    }

    @Transactional(readOnly = true)
    public AgentScenarioResponse detail(String code) {
        AgentScenarioResponse response = toResponse(requireDefinition(code), requireEntity(code));
        response.getParameters().forEach(parameter -> {
            if ("select".equalsIgnoreCase(parameter.getType())) {
                parameter.setOptions(definitionParameterOptionService.options(code, parameter.getKey()));
            }
        });
        return response;
    }

    @Transactional(readOnly = true)
    public AgentScenarioEntity requireEntity(String code) {
        return agentScenarioRepository.findById(code)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.VALIDATION_FAILED,
                        "Scenario not found"));
    }

    public ModuleDefinition requireDefinition(String code) {
        return moduleDefinitionService.listInstalledScenarios().stream()
                .filter(definition -> definition.getCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.VALIDATION_FAILED,
                        "Scenario not found"));
    }

    @Transactional(readOnly = true)
    public AgentTaskDefinition requireTaskDefinition(String code) {
        ModuleDefinition module = requireDefinition(code);
        List<AgentTaskDefinition.DefinitionGuide> guides = (module.getGuides() == null
                ? List.<ModuleGuideDefinition>of() : module.getGuides()).stream()
                .sorted(Comparator.comparing(ModuleGuideDefinition::getSortOrder,
                        Comparator.nullsLast(Integer::compareTo)))
                .map(guide -> new AgentTaskDefinition.DefinitionGuide(guide.getKey(), guide.getTitle(),
                        guide.getDescription(), moduleDefinitionService.readGuide(module, guide.getFile())))
                .toList();
        AgentTaskDefinition definition = AgentTaskDefinition.fromModule(
                module, moduleDefinitionService.readPrompt(module), guides);
        AgentScenarioEntity state = requireEntity(code);
        definition.setCapabilities(copyStringSet(state.getCapabilities()));
        definition.setCapabilityCommands(copyCapabilityCommands(state.getCapabilityCommands()));
        return definition;
    }

    /**
     * 只更新场景的可变平台状态和运行授权，manifest 和 Markdown 始终由安装目录提供。
     *
     * @param code 场景编码
     * @param request 管理页面提交的启用、可见、Skill 和命令 code 配置
     * @return 合并文件定义后的场景响应
     */
    @Transactional
    public AgentScenarioResponse update(String code, AgentScenarioStateUpdateRequest request) {
        ModuleDefinition definition = requireDefinition(code);
        AgentScenarioEntity entity = requireEntity(code);
        entity.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        entity.setUserVisible(Boolean.TRUE.equals(request.getUserVisible()));
        Set<String> capabilities = copyStringSet(request.getCapabilities());
        Map<String, ModuleDefinition> availableCapabilities = moduleDefinitionService.listCapabilities().stream()
                .collect(java.util.stream.Collectors.toMap(ModuleDefinition::getCode, item -> item));
        Set<String> unknownCapabilities = capabilities.stream()
                .filter(capabilityCode -> !availableCapabilities.containsKey(capabilityCode))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!unknownCapabilities.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                    "Skill 不存在：" + String.join(", ", unknownCapabilities));
        }
        Map<String, Set<String>> capabilityCommands = copyCapabilityCommands(request.getCapabilityCommands());
        for (Map.Entry<String, Set<String>> entry : capabilityCommands.entrySet()) {
            if (!capabilities.contains(entry.getKey())) {
                throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                        "未选择 Skill，不能配置命令：" + entry.getKey());
            }
            var extension = moduleDefinitionService.readCapabilityExtension(
                    availableCapabilities.get(entry.getKey()).getModuleDirectory());
            Set<String> supportedCommands = (extension.getCommands() == null
                    ? List.<top.fusb.lingxi.definition.CapabilityCommandExtensionDefinition>of()
                    : extension.getCommands()).stream()
                    .map(command -> command.getCommand().trim().replaceAll("\\s+", "."))
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> unknownCommands = entry.getValue().stream()
                    .filter(commandCode -> !supportedCommands.contains(commandCode))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (!unknownCommands.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                        "Skill 命令不存在：" + entry.getKey() + " / " + String.join(", ", unknownCommands));
            }
        }
        entity.setCapabilities(capabilities);
        entity.setCapabilityCommands(capabilityCommands);
        entity.setUpdatedAt(LocalDateTime.now());
        return toResponse(definition, agentScenarioRepository.save(entity));
    }

    @Transactional
    public List<AgentScenarioResponse> reorder(List<String> codes) {
        LinkedHashSet<String> orderedCodes = new LinkedHashSet<>(codes == null ? List.of() : codes);
        List<AgentScenarioEntity> scenarios = agentScenarioRepository.findAllByOrderBySortOrderAsc();
        Map<String, AgentScenarioEntity> scenarioByCode = scenarios.stream()
                .collect(java.util.stream.Collectors.toMap(AgentScenarioEntity::getCode, state -> state));
        if (orderedCodes.size() != scenarios.size() || !scenarioByCode.keySet().equals(orderedCodes)) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "场景列表已变化，请刷新后重试");
        }
        LocalDateTime now = LocalDateTime.now();
        int sortOrder = 1;
        for (String code : orderedCodes) {
            AgentScenarioEntity state = scenarioByCode.get(code);
            state.setSortOrder(sortOrder++);
            state.setUpdatedAt(now);
        }
        agentScenarioRepository.saveAll(scenarioByCode.values());
        return listAll();
    }

    /**
     * 为磁盘上的场景建立或刷新最小运行状态。
     *
     * @param definition 已通过文件校验的场景定义
     * @return 当前状态实体
     */
    @Transactional
    public AgentScenarioEntity syncDefinition(ModuleDefinition definition) {
        AgentScenarioEntity entity = agentScenarioRepository.findById(definition.getCode()).orElseGet(() -> {
            AgentScenarioEntity created = new AgentScenarioEntity();
            created.setCode(definition.getCode());
            created.setEnabled(Boolean.TRUE.equals(definition.getEnabled()));
            created.setUserVisible(definition.getUserVisible() == null || Boolean.TRUE.equals(definition.getUserVisible()));
            created.setSortOrder(agentScenarioRepository.findMaxSortOrder() + 1);
            created.setCapabilities(copyStringSet(definition.getCapabilities()));
            created.setCapabilityCommands(defaultCapabilityCommands(definition));
            created.setCreatedAt(LocalDateTime.now());
            return created;
        });
        entity.setUpdatedAt(LocalDateTime.now());
        AgentScenarioEntity saved = agentScenarioRepository.save(entity);
        log.info("Synchronized scenario state code={} enabled={}", saved.getCode(), saved.isEnabled());
        return saved;
    }

    @Transactional
    public AgentScenarioResponse installOrUpdatePackage(ModuleDefinition definition, String packageHash) {
        AgentScenarioEntity state = syncDefinition(definition);
        log.info("Installed scenario definition code={} version={} hash={}",
                definition.getCode(), definition.getVersion(), packageHash);
        return toResponse(definition, state);
    }

    /**
     * 删除已停用场景的运行状态，并清理分析情境中的场景引用。
     *
     * @param code 已从安装目录移出的场景编码
     * @return 无返回值
     * @throws BizException 场景仍启用或存在未结束任务时抛出
     */
    @Transactional
    public void uninstallState(String code) {
        AgentScenarioEntity entity = requireEntity(code);
        if (entity.isEnabled()) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                    "请先停用场景再卸载");
        }
        if (agentTaskRepository.existsByScenarioCodeAndStatusIn(code,
                List.of(TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.WAITING_USER))) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                    "场景仍有未结束任务，暂时不能卸载");
        }

        LocalDateTime now = LocalDateTime.now();
        List<AnalysisPremiseEntity> changedPremises = analysisPremiseRepository.findAll().stream()
                .filter(premise -> {
                    Set<String> visibleCodes = new LinkedHashSet<>(premise.getVisibleScenarioCodes() == null
                            ? Set.of() : premise.getVisibleScenarioCodes());
                    Map<String, Map<String, String>> contextValues = new LinkedHashMap<>(
                            premise.getScenarioContextValues() == null
                                    ? Map.of() : premise.getScenarioContextValues());
                    boolean visibleChanged = visibleCodes.remove(code);
                    boolean contextChanged = contextValues.remove(code) != null;
                    boolean changed = visibleChanged || contextChanged;
                    if (changed) {
                        premise.setVisibleScenarioCodes(visibleCodes);
                        premise.setScenarioContextValues(contextValues);
                        premise.setUpdatedAt(now);
                    }
                    return changed;
                })
                .toList();
        if (!changedPremises.isEmpty()) {
            analysisPremiseRepository.saveAll(changedPremises);
        }
        agentScenarioRepository.delete(entity);
        log.info("Uninstalled scenario state code={} cleanedPremiseCount={}", code, changedPremises.size());
    }

    private Map<String, ModuleDefinition> definitionMap() {
        Map<String, ModuleDefinition> result = new LinkedHashMap<>();
        moduleDefinitionService.listInstalledScenarios().forEach(definition -> result.put(definition.getCode(), definition));
        return result;
    }

    private AgentScenarioResponse toResponse(ModuleDefinition definition, AgentScenarioEntity state) {
        AgentScenarioResponse response = new AgentScenarioResponse();
        response.setCode(definition.getCode());
        response.setName(definition.getName());
        response.setDescription(definition.getDescription());
        response.setSlogan(definition.getSlogan());
        response.setScenario(definition.getScenario());
        response.setInputMode(definition.getInputMode() == null
                ? ScenarioInputMode.CONVERSATION : definition.getInputMode());
        response.setIcon(definition.getIcon());
        response.setIconUrl(DefinitionAssetUrlKit.iconUrl("scenarios", definition.getCode(),
                definition.getIcon(), state.getUpdatedAt()));
        response.setColor(definition.getColor());
        response.setPromptText(moduleDefinitionService.readPrompt(definition));
        response.setEnabled(state.isEnabled());
        response.setUninstallable(definition.isInstalled());
        response.setUserVisible(state.isUserVisible());
        response.setPackageVersion(definition.getVersion());
        response.setSortOrder(state.getSortOrder());
        response.setUniqueBySource(Boolean.TRUE.equals(definition.getUniqueBySource()));
        response.setResultFormat(definition.getResultFormat());
        response.setResultRenderer(definition.getResultRenderer());
        response.setPresentations(copyStringSet(definition.getPresentations()));
        response.setQueuePriority(definition.getQueuePriority());
        response.setCapabilities(copyStringSet(state.getCapabilities()));
        response.setCapabilityCommands(copyCapabilityCommands(state.getCapabilityCommands()));
        response.setCapabilityConditions(copyCapabilityConditions(definition.getCapabilityConditions()));
        response.setParameters((definition.getParameters() == null
                        ? List.<ModuleParameterDefinition>of() : definition.getParameters()).stream()
                .sorted(Comparator.comparing(ModuleParameterDefinition::getSortOrder,
                        Comparator.nullsLast(Integer::compareTo)))
                .map(definitionSupport::parameterResponse)
                .toList());
        response.setGuides((definition.getGuides() == null
                        ? List.<ModuleGuideDefinition>of() : definition.getGuides()).stream()
                .sorted(Comparator.comparing(ModuleGuideDefinition::getSortOrder,
                        Comparator.nullsLast(Integer::compareTo)))
                .map(guide -> definitionSupport.guideResponse(guide,
                        moduleDefinitionService.readGuide(definition, guide.getFile())))
                .toList());
        response.setCreatedAt(state.getCreatedAt());
        response.setUpdatedAt(state.getUpdatedAt());
        return response;
    }

    private PublicScenarioResponse toPublicResponse(ModuleDefinition definition, AgentScenarioEntity state) {
        PublicScenarioResponse response = new PublicScenarioResponse();
        response.setCode(definition.getCode());
        response.setName(definition.getName());
        response.setDescription(definition.getDescription());
        response.setSlogan(definition.getSlogan());
        response.setIconUrl(DefinitionAssetUrlKit.iconUrl("scenarios", definition.getCode(),
                definition.getIcon(), state.getUpdatedAt()));
        response.setColor(definition.getColor());
        return response;
    }

    private Set<String> copyStringSet(Set<String> values) {
        return values == null ? new LinkedHashSet<>() : new LinkedHashSet<>(values);
    }

    private Map<String, Set<String>> copyCapabilityCommands(Map<String, Set<String>> values) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((code, commands) -> result.put(code,
                    commands == null ? new LinkedHashSet<>() : new LinkedHashSet<>(commands)));
        }
        return result;
    }

    private Map<String, Set<String>> defaultCapabilityCommands(ModuleDefinition scenarioDefinition) {
        if (scenarioDefinition.getCapabilityCommands() != null) {
            return copyCapabilityCommands(scenarioDefinition.getCapabilityCommands());
        }
        Map<String, ModuleDefinition> definitions = moduleDefinitionService.listCapabilities().stream()
                .collect(java.util.stream.Collectors.toMap(ModuleDefinition::getCode, item -> item));
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String capabilityCode : copyStringSet(scenarioDefinition.getCapabilities())) {
            ModuleDefinition capability = definitions.get(capabilityCode);
            if (capability == null) {
                continue;
            }
            var extension = moduleDefinitionService.readCapabilityExtension(capability.getModuleDirectory());
            Set<String> commands = (extension.getCommands() == null
                    ? List.<top.fusb.lingxi.definition.CapabilityCommandExtensionDefinition>of()
                    : extension.getCommands()).stream()
                    .map(command -> command.getCommand().trim().replaceAll("\\s+", "."))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            result.put(capabilityCode, commands);
        }
        return result;
    }

    private Map<String, CapabilityActivationCondition> copyCapabilityConditions(
            Map<String, CapabilityActivationCondition> values) {
        Map<String, CapabilityActivationCondition> result = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((code, source) -> {
                if (source != null) {
                    CapabilityActivationCondition condition = new CapabilityActivationCondition();
                    condition.setParameter(source.getParameter());
                    condition.setValues(copyStringSet(source.getValues()));
                    result.put(code, condition);
                }
            });
        }
        return result;
    }
}
