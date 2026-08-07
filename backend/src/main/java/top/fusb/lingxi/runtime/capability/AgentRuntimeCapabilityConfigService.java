package top.fusb.lingxi.runtime.capability;

import top.fusb.lingxi.dto.TaskInputValue;
import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.entity.CapabilityConfigEntity;
import top.fusb.lingxi.entity.TaskRuntimeContextValueEntity;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.kit.TextKit;
import top.fusb.lingxi.repository.AgentTaskRepository;
import top.fusb.lingxi.repository.TaskRuntimeContextValueRepository;
import top.fusb.lingxi.runtime.capability.dto.AgentRuntimeCapabilityConfigInfo;
import top.fusb.lingxi.runtime.capability.dto.AgentRuntimeCapabilityConfigSummary;
import top.fusb.lingxi.runtime.capability.dto.AgentRuntimeContextPutRequest;
import top.fusb.lingxi.runtime.capability.dto.AgentRuntimeContextResponse;
import top.fusb.lingxi.runtime.capability.dto.AgentRuntimeContextValueResponse;
import top.fusb.lingxi.service.CapabilityConfigService;
import top.fusb.lingxi.task.TaskAgentWorkspaceService;
import top.fusb.lingxi.task.TaskStoragePathService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AgentRuntimeCapabilityConfigService {

    private static final int MAX_CONTEXT_VALUE_COUNT = 50;
    private static final int MAX_CONTEXT_KEY_LENGTH = 100;
    private static final int MAX_CONTEXT_VALUE_LENGTH = 20_000;

    private final CapabilityConfigService capabilityConfigService;
    private final top.fusb.lingxi.service.AgentCapabilityService agentCapabilityService;
    private final AgentTaskRepository agentTaskRepository;
    private final TaskRuntimeContextValueRepository contextValueRepository;
    private final TaskAgentWorkspaceService taskAgentWorkspaceService;
    private final TaskStoragePathService taskStoragePathService;

    /**
     * 查询能力运行时可用的本地服务配置。
     *
     * @param taskId 任务 ID
     * @return 本地服务配置摘要
     */
    @Transactional(readOnly = true)
    public List<AgentRuntimeCapabilityConfigSummary> list(Long taskId) {
        Set<String> capabilityCodes = agentTaskRepository.findTaskCapabilityCodesByTaskId(taskId);
        return capabilityConfigService.listEnabledConfigs().stream()
                .filter(config -> capabilityCodes.contains(config.getCapabilityCode()))
                .map(this::localSummary)
                .toList();
    }

    /**
     * 查询当前任务上下文摘要。
     *
     * @param taskId 任务 ID
     * @return 当前任务输入、运行时上下文和本地服务配置摘要
     */
    @Transactional(readOnly = true)
    public AgentRuntimeContextResponse context(Long taskId) {
        AgentTaskEntity task = requireTask(taskId);
        AgentRuntimeContextResponse response = new AgentRuntimeContextResponse();
        response.setTaskId(taskId);
        response.setScenario(task.getScenario());
        response.setInputValues(task.getInputValues() == null ? List.of() : task.getInputValues());
        response.setValues(contextValueRepository.findByTaskIdOrderByContextKeyAsc(taskId).stream()
                .map(this::contextValueResponse)
                .toList());
        response.setCapabilityConfigs(list(taskId));
        return response;
    }

    /**
     * 查询本地服务配置详情。
     *
     * @param id 本地服务配置 ID
     * @param taskId 任务 ID
     * @return 本地服务配置详情
     */
    @Transactional(readOnly = true)
    public AgentRuntimeCapabilityConfigInfo detail(Long id, Long taskId) {
        AgentTaskEntity task = requireTask(taskId);
        CapabilityConfigEntity config = capabilityConfigService.requireEnabledConfig(id);
        if (!agentTaskRepository.findTaskCapabilityCodesByTaskId(task.getId()).contains(config.getCapabilityCode())) {
            throw new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.DATA_LOAD_FAILED, "能力配置不存在");
        }
        return localInfo(config);
    }

    /**
     * 写入当前任务上下文值。
     *
     * @param taskId 任务 ID
     * @param request 上下文值
     * @return 写入后的上下文
     */
    @Transactional
    public AgentRuntimeContextResponse putContext(Long taskId, AgentRuntimeContextPutRequest request) {
        AgentTaskEntity task = requireTask(taskId);
        LocalDateTime now = LocalDateTime.now();
        if (request != null && request.getValues() != null) {
            if (request.getValues().size() > MAX_CONTEXT_VALUE_COUNT) {
                throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "单次写入的任务上下文过多");
            }
            for (AgentRuntimeContextValueResponse value : request.getValues()) {
                upsertValue(task, value, now);
            }
        }
        taskAgentWorkspaceService.refreshContext(taskId, workspaceRoot(taskId));
        return context(taskId);
    }

    private Path workspaceRoot(Long taskId) {
        return taskStoragePathService.workspaceRoot(taskId);
    }

    private void upsertValue(AgentTaskEntity task, AgentRuntimeContextValueResponse value, LocalDateTime now) {
        if (value == null || TextKit.blankToNull(value.getKey()) == null) {
            return;
        }
        String key = value.getKey().trim();
        if (key.length() > MAX_CONTEXT_KEY_LENGTH || !key.matches("[\\p{L}_][\\p{L}\\p{N}_.-]{0,99}")) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "任务上下文键格式错误");
        }
        if (value.getValue() != null && value.getValue().length() > MAX_CONTEXT_VALUE_LENGTH) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "任务上下文值过长");
        }
        TaskRuntimeContextValueEntity entity = contextValueRepository.findByTaskIdAndContextKey(task.getId(), key)
                .orElseGet(TaskRuntimeContextValueEntity::new);
        if (entity.getId() == null) {
            entity.setTask(task);
            entity.setContextKey(key);
            entity.setCreatedAt(now);
        }
        entity.setContextValue(value.getValue());
        entity.setUpdatedAt(now);
        contextValueRepository.save(entity);
    }

    private AgentTaskEntity requireTask(Long taskId) {
        if (taskId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "缺少任务 ID");
        }
        return agentTaskRepository.findWithDetailsById(taskId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.TASK_NOT_FOUND));
    }

    private AgentRuntimeCapabilityConfigSummary localSummary(CapabilityConfigEntity config) {
        AgentRuntimeCapabilityConfigSummary summary = new AgentRuntimeCapabilityConfigSummary();
        summary.setId(config.getId());
        summary.setRuntime(false);
        summary.setName(config.getName());
        summary.setCapabilityCode(config.getCapabilityCode());
        summary.setCapabilityName(agentCapabilityService.requireDefinition(config.getCapabilityCode()).getName());
        summary.setDescription(config.getDescription());
        return summary;
    }

    private AgentRuntimeCapabilityConfigInfo localInfo(CapabilityConfigEntity config) {
        AgentRuntimeCapabilityConfigInfo info = new AgentRuntimeCapabilityConfigInfo();
        info.setId(config.getId());
        info.setName(config.getName());
        info.setCapabilityCode(config.getCapabilityCode());
        info.setCapabilityName(agentCapabilityService.requireDefinition(config.getCapabilityCode()).getName());
        info.setDescription(config.getDescription());
        info.setConfig(config.getConfig());
        return info;
    }

    private AgentRuntimeContextValueResponse contextValueResponse(TaskRuntimeContextValueEntity entity) {
        AgentRuntimeContextValueResponse response = new AgentRuntimeContextValueResponse();
        response.setKey(entity.getContextKey());
        response.setValue(entity.getContextValue());
        return response;
    }
}
