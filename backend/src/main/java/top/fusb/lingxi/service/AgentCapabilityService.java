package top.fusb.lingxi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.fusb.lingxi.definition.ModuleDefinition;
import top.fusb.lingxi.definition.ModuleGuideDefinition;
import top.fusb.lingxi.definition.ModuleParameterDefinition;
import top.fusb.lingxi.dto.AgentCapabilityResponse;
import top.fusb.lingxi.dto.AgentCapabilityStateUpdateRequest;
import top.fusb.lingxi.dto.CapabilityCommandDefinition;
import top.fusb.lingxi.dto.PageResult;
import top.fusb.lingxi.entity.AgentCapabilityEntity;
import top.fusb.lingxi.entity.AgentScenarioEntity;
import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.enums.TaskStatus;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.resource.ResourceCatalogItemRepository;
import top.fusb.lingxi.runtime.api.event.RuntimeActionIcon;
import top.fusb.lingxi.kit.DefinitionAssetUrlKit;
import top.fusb.lingxi.repository.AgentCapabilityRepository;
import top.fusb.lingxi.repository.AgentScenarioRepository;
import top.fusb.lingxi.repository.AgentTaskRepository;
import top.fusb.lingxi.repository.CapabilityConfigRepository;

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
public class AgentCapabilityService {

    private final AgentCapabilityRepository agentCapabilityRepository;
    private final AgentScenarioRepository agentScenarioRepository;
    private final AgentTaskRepository agentTaskRepository;
    private final CapabilityConfigRepository capabilityConfigRepository;
    private final ResourceCatalogItemRepository resourceCatalogItemRepository;
    private final ModuleDefinitionService moduleDefinitionService;
    private final AgentDefinitionSupport definitionSupport;

    @Transactional(readOnly = true)
    public List<AgentCapabilityResponse> listAll() {
        Map<String, AgentCapabilityEntity> states = stateMap();
        return moduleDefinitionService.listCapabilities().stream()
                .map(definition -> toResponse(definition, states.get(definition.getCode())))
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResult<AgentCapabilityResponse> pageAll(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);
        List<AgentCapabilityResponse> values = listAll();
        int from = Math.min((safePage - 1) * safeSize, values.size());
        int to = Math.min(from + safeSize, values.size());
        return new PageResult<>(values.subList(from, to), values.size(), safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public AgentCapabilityResponse detail(String code) {
        ModuleDefinition definition = requireDefinition(code);
        return toResponse(definition, requireEntity(code));
    }

    @Transactional(readOnly = true)
    public AgentCapabilityEntity requireEntity(String code) {
        return agentCapabilityRepository.findById(code)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.VALIDATION_FAILED,
                        "Capability not found"));
    }

    public ModuleDefinition requireDefinition(String code) {
        return moduleDefinitionService.listCapabilities().stream()
                .filter(definition -> definition.getCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.VALIDATION_FAILED,
                        "Capability not found"));
    }

    /**
     * 从场景声明中筛出已启用且 Skill 文件实际存在的能力编码。
     *
     * @param requestedCodes 场景按参数条件激活的能力编码
     * @return 保持场景声明顺序的本次任务可挂载能力编码
     */
    @Transactional(readOnly = true)
    public Set<String> availableRuntimeCodes(Set<String> requestedCodes) {
        if (requestedCodes == null || requestedCodes.isEmpty()) {
            return Set.of();
        }
        Set<String> installedCodes = moduleDefinitionService.listCapabilities().stream()
                .map(ModuleDefinition::getCode)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> enabledCodes = agentCapabilityRepository.findAllByCodeIn(requestedCodes).stream()
                .filter(AgentCapabilityEntity::isEnabled)
                .map(AgentCapabilityEntity::getCode)
                .filter(installedCodes::contains)
                .collect(java.util.stream.Collectors.toSet());
        return requestedCodes.stream().filter(enabledCodes::contains)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 只更新 Skill 的平台运行状态，固定定义继续由安装目录提供。
     *
     * @param code Skill 编码
     * @param request 管理页面提交的数据；仅 enabled 属于可持久化状态
     * @return 合并文件定义后的 Skill 响应
     */
    @Transactional
    public AgentCapabilityResponse update(String code, AgentCapabilityStateUpdateRequest request) {
        ModuleDefinition definition = requireDefinition(code);
        AgentCapabilityEntity entity = requireEntity(code);
        entity.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        entity.setUpdatedAt(LocalDateTime.now());
        return toResponse(definition, agentCapabilityRepository.save(entity));
    }

    /**
     * 为磁盘上的 Skill 建立或刷新最小运行状态。
     *
     * @param definition 已通过文件校验的 Skill 定义
     * @return 当前状态实体
     */
    @Transactional
    public AgentCapabilityEntity syncDefinition(ModuleDefinition definition) {
        AgentCapabilityEntity entity = agentCapabilityRepository.findById(definition.getCode()).orElseGet(() -> {
            AgentCapabilityEntity created = new AgentCapabilityEntity();
            created.setCode(definition.getCode());
            created.setEnabled(Boolean.TRUE.equals(definition.getEnabled()));
            created.setCreatedAt(LocalDateTime.now());
            return created;
        });
        entity.setUpdatedAt(LocalDateTime.now());
        AgentCapabilityEntity saved = agentCapabilityRepository.save(entity);
        log.info("Synchronized Skill state code={} enabled={}", saved.getCode(), saved.isEnabled());
        return saved;
    }

    @Transactional
    public AgentCapabilityResponse installOrUpdatePackage(ModuleDefinition definition, String packageHash) {
        AgentCapabilityEntity state = syncDefinition(definition);
        log.info("Installed Skill definition code={} version={} hash={}",
                definition.getCode(), definition.getVersion(), packageHash);
        return toResponse(definition, state);
    }

    /**
     * 删除已停用 Skill 的运行状态和私有配置，并从场景授权中移除该 Skill。
     *
     * @param code 已从安装目录移出的 Skill 编码
     * @return 无返回值
     * @throws BizException Skill 仍启用或存在未结束任务时抛出
     */
    @Transactional
    public void uninstallState(String code) {
        AgentCapabilityEntity entity = requireEntity(code);
        if (entity.isEnabled()) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                    "请先停用能力再卸载");
        }
        boolean usedByActiveTask = agentTaskRepository.findByStatusIn(
                        List.of(TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.WAITING_USER)).stream()
                .map(AgentTaskEntity::getEnabledCapabilityCodes)
                .filter(java.util.Objects::nonNull)
                .anyMatch(codes -> codes.contains(code));
        if (usedByActiveTask) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                    "能力仍被未结束任务使用，暂时不能卸载");
        }

        LocalDateTime now = LocalDateTime.now();
        List<AgentScenarioEntity> changedScenarios = new java.util.ArrayList<>();
        for (AgentScenarioEntity scenario : agentScenarioRepository.findAllByOrderBySortOrderAsc()) {
            if (scenario.getCapabilities() == null || !scenario.getCapabilities().contains(code)) {
                continue;
            }
            Set<String> capabilities = new LinkedHashSet<>(scenario.getCapabilities());
            capabilities.remove(code);
            Map<String, Set<String>> commands = new LinkedHashMap<>(scenario.getCapabilityCommands() == null
                    ? Map.of() : scenario.getCapabilityCommands());
            commands.remove(code);
            scenario.setCapabilities(capabilities);
            scenario.setCapabilityCommands(commands);
            scenario.setUpdatedAt(now);
            changedScenarios.add(scenario);
        }
        if (!changedScenarios.isEmpty()) {
            agentScenarioRepository.saveAll(changedScenarios);
        }
        long configCount = capabilityConfigRepository.deleteByCapabilityCode(code);
        long resourceCount = resourceCatalogItemRepository.deleteByProviderCode(code);
        agentCapabilityRepository.delete(entity);
        log.info("Uninstalled Skill state code={} cleanedScenarioCount={} configCount={} resourceCount={}",
                code, changedScenarios.size(), configCount, resourceCount);
    }

    private Map<String, AgentCapabilityEntity> stateMap() {
        Map<String, AgentCapabilityEntity> result = new LinkedHashMap<>();
        agentCapabilityRepository.findAllByOrderByCodeAsc().forEach(state -> result.put(state.getCode(), state));
        return result;
    }

    private AgentCapabilityResponse toResponse(ModuleDefinition definition, AgentCapabilityEntity state) {
        AgentCapabilityResponse response = new AgentCapabilityResponse();
        response.setCode(definition.getCode());
        response.setName(definition.getName());
        response.setDescription(definition.getDescription());
        response.setIcon(definition.getIcon());
        response.setIconUrl(DefinitionAssetUrlKit.iconUrl("capabilities", definition.getCode(),
                definition.getIcon(), state == null ? null : state.getUpdatedAt()));
        response.setPromptText(moduleDefinitionService.readPrompt(definition));
        response.setEnabled(state != null && state.isEnabled());
        response.setUninstallable(definition.isInstalled());
        response.setPackageVersion(definition.getVersion());
        response.setConfigParameters(parameters(definition.getConfig()));
        response.setParameters(parameters(definition.getParameters()));
        response.setGuides(guides(definition));
        response.setCommands(commands(definition));
        response.setCreatedAt(state == null ? null : state.getCreatedAt());
        response.setUpdatedAt(state == null ? null : state.getUpdatedAt());
        return response;
    }

    private List<top.fusb.lingxi.dto.AgentDefinitionParameterResponse> parameters(
            List<ModuleParameterDefinition> definitions) {
        return (definitions == null ? List.<ModuleParameterDefinition>of() : definitions).stream()
                .sorted(Comparator.comparing(ModuleParameterDefinition::getSortOrder,
                        Comparator.nullsLast(Integer::compareTo)))
                .map(definitionSupport::parameterResponse)
                .toList();
    }

    private List<top.fusb.lingxi.dto.AgentDefinitionGuideResponse> guides(ModuleDefinition definition) {
        return (definition.getGuides() == null ? List.<ModuleGuideDefinition>of() : definition.getGuides()).stream()
                .sorted(Comparator.comparing(ModuleGuideDefinition::getSortOrder,
                        Comparator.nullsLast(Integer::compareTo)))
                .map(guide -> definitionSupport.guideResponse(guide,
                        moduleDefinitionService.readGuide(definition, guide.getFile())))
                .toList();
    }

    private List<CapabilityCommandDefinition> commands(ModuleDefinition module) {
        var extension = moduleDefinitionService.readCapabilityExtension(module.getModuleDirectory());
        return (extension.getCommands() == null
                ? List.<top.fusb.lingxi.definition.CapabilityCommandExtensionDefinition>of()
                : extension.getCommands()).stream().map(source -> {
            String command = source.getCommand().trim().replaceAll("\\s+", " ");
            CapabilityCommandDefinition target = new CapabilityCommandDefinition();
            target.setModuleCode(module.getCode());
            target.setCode(command.replace(' ', '.'));
            target.setName(source.getDisplayName());
            target.setIcon(RuntimeActionIcon.fromExternalValue(source.getIcon()));
            target.setCommand(command);
            target.setDescription(source.getDescription());
            target.setOutputs(source.getOutputs() == null ? List.of() : source.getOutputs());
            return target;
        }).toList();
    }
}
