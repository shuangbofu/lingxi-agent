package top.fusb.lingxi.task;

import top.fusb.lingxi.dto.TaskShareCreateResponse;
import top.fusb.lingxi.dto.TaskShareCreateRequest;
import top.fusb.lingxi.dto.TaskSharePreviewResponse;
import top.fusb.lingxi.dto.TaskShareRoundPreviewResponse;
import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.entity.TaskShareEntity;
import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.enums.TaskStatus;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.kit.PasswordKit;
import top.fusb.lingxi.repository.TaskShareRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskShareService {

    private static final String CODE_CHARS = "abcdefghijkmnpqrstuvwxyz23456789";
    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final TaskService taskService;
    private final TaskContentService taskContentService;
    private final TaskShareRepository taskShareRepository;

    /**
     * 为当前用户可访问的任务创建分享密码。
     *
     * @param taskId 任务 ID
     * @param request 需要分享的会话轮次
     * @param currentUser 当前登录用户
     * @return 分享码和本次生成的明文密码
     * @throws BizException 任务不存在或无权限时抛出
     */
    @Transactional
    public TaskShareCreateResponse create(Long taskId, TaskShareCreateRequest request, UserEntity currentUser) {
        AgentTaskEntity task = taskService.requireAccessibleEntity(taskId, currentUser);
        Long rootId = task.getConversationRootTaskId() == null ? task.getId() : task.getConversationRootTaskId();
        List<AgentTaskEntity> conversationRounds = taskService.conversationRounds(rootId);
        List<AgentTaskEntity> availableRounds = conversationRounds.isEmpty() ? List.of(task) : conversationRounds;
        AgentTaskEntity rootTask = availableRounds.stream()
                .filter(round -> rootId.equals(round.getId()))
                .findFirst()
                .orElse(availableRounds.get(0));
        Set<Long> requestedIds = request == null || request.getRoundTaskIds() == null || request.getRoundTaskIds().isEmpty()
                ? Set.of(taskId)
                : new LinkedHashSet<>(request.getRoundTaskIds());
        Set<Long> availableRoundIds = availableRounds.stream().map(AgentTaskEntity::getId).collect(Collectors.toSet());
        if (!availableRoundIds.containsAll(requestedIds)) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "只能分享当前会话中的轮次");
        }
        List<AgentTaskEntity> selectedRounds = availableRounds.stream()
                .filter(round -> requestedIds.contains(round.getId()))
                .toList();
        for (AgentTaskEntity round : selectedRounds) {
            if (round.getStatus() != TaskStatus.SUCCESS) {
                throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "只能分享已成功完成的轮次");
            }
            String resultText = taskContentService.read(round.getId(), "result", round.getResultText());
            if ((resultText == null || resultText.isBlank()) && round.getResultData() == null) {
                throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "所选轮次暂无可分享的分析结果");
            }
        }
        TaskShareEntity existing = taskShareRepository.findFirstByTaskIdOrderByCreatedAtAsc(rootTask.getId()).orElse(null);
        String password = randomText(PASSWORD_CHARS, 6);
        TaskShareEntity share = existing == null ? new TaskShareEntity() : existing;
        if (existing == null) {
            share.setShareCode(uniqueShareCode());
            share.setTask(rootTask);
            share.setCreatedBy(currentUser);
            share.setCreatedAt(LocalDateTime.now());
        }
        share.setSharedRounds(new LinkedHashSet<>(selectedRounds));
        share.setPasswordHash(PasswordKit.hash(password));
        share.setUpdatedAt(LocalDateTime.now());
        taskShareRepository.save(share);
        log.info("创建任务分享 shareCode={} rootTaskId={} roundTaskIds={} username={}", share.getShareCode(),
                rootTask.getId(), requestedIds, currentUser == null ? null : currentUser.getUsername());
        return toCreateResponse(share, password);
    }

    private TaskShareCreateResponse toCreateResponse(TaskShareEntity share, String password) {
        TaskShareCreateResponse response = new TaskShareCreateResponse();
        response.setShareCode(share.getShareCode());
        response.setPassword(password);
        return response;
    }

    /**
     * 校验分享密码并返回问题与回答。
     *
     * @param shareCode 分享码
     * @param password 分享密码
     * @return 分享预览内容
     * @throws BizException 分享不存在或密码错误时抛出
     */
    public TaskSharePreviewResponse preview(String shareCode, String password) {
        TaskShareEntity share = taskShareRepository.findWithTaskByShareCode(shareCode)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.TASK_NOT_FOUND, "分享不存在"));
        if (!PasswordKit.matches(password, share.getPasswordHash())) {
            throw new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.BAD_CREDENTIALS, "分享密码错误");
        }
        log.info("访问任务分享 shareCode={} taskId={}", shareCode, share.getTask().getId());
        return toPreviewResponse(share, true);
    }

    /**
     * 返回分享访问页需要展示的基础信息，不包含任务结果正文。
     *
     * @param shareCode 分享码
     * @return 分享标题、分享人和任务基础信息
     * @throws BizException 分享不存在时抛出
     */
    public TaskSharePreviewResponse meta(String shareCode) {
        TaskShareEntity share = taskShareRepository.findWithTaskByShareCode(shareCode)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.TASK_NOT_FOUND, "分享不存在"));
        return toPreviewResponse(share, false);
    }

    private TaskSharePreviewResponse toPreviewResponse(TaskShareEntity share, boolean includeResults) {
        AgentTaskEntity task = share.getTask();
        AgentTaskEntity rootTask = conversationRoot(task);
        List<AgentTaskEntity> selectedRounds = selectedRounds(share);
        AgentTaskEntity latestRound = selectedRounds.get(selectedRounds.size() - 1);
        TaskSharePreviewResponse response = new TaskSharePreviewResponse();
        response.setShareCode(share.getShareCode());
        response.setTaskId(rootTask.getId());
        UserEntity sharedBy = share.getCreatedBy() == null ? task.getOwner() : share.getCreatedBy();
        response.setSharedByUsername(sharedBy == null ? null : sharedBy.getUsername());
        response.setSharedByDisplayName(sharedBy == null ? null : sharedBy.getDisplayName());
        response.setScenarioName(rootTask.getScenarioName());
        response.setScenario(rootTask.getScenario());
        response.setResultRenderer(resultRenderer(latestRound));
        response.setStatus(latestRound.getStatus());
        response.setTitle(rootTask.getTitle());
        response.setUserInput(rootTask.getUserInput());
        if (selectedRounds.size() == 1) {
            response.setSharedRoundNo(latestRound.getRoundNo() == null ? 1 : latestRound.getRoundNo());
            response.setSharedRoundUserInput(latestRound.getUserInput());
        }
        if (includeResults) {
            response.setResultText(taskContentService.read(latestRound.getId(), "result", latestRound.getResultText()));
            response.setResultData(latestRound.getResultData());
        }
        response.setRounds(selectedRounds.stream().map(round -> roundPreview(round, includeResults)).toList());
        response.setStartedAt(latestRound.getStartedAt());
        response.setEndedAt(latestRound.getEndedAt());
        response.setCreatedAt(rootTask.getCreatedAt());
        return response;
    }

    private List<AgentTaskEntity> selectedRounds(TaskShareEntity share) {
        if (share.getSharedRounds() == null || share.getSharedRounds().isEmpty()) {
            return List.of(share.getTask());
        }
        return share.getSharedRounds().stream()
                .sorted((left, right) -> Integer.compare(left.getRoundNo() == null ? 1 : left.getRoundNo(),
                        right.getRoundNo() == null ? 1 : right.getRoundNo()))
                .toList();
    }

    private TaskShareRoundPreviewResponse roundPreview(AgentTaskEntity round, boolean includeResult) {
        TaskShareRoundPreviewResponse response = new TaskShareRoundPreviewResponse();
        response.setTaskId(round.getId());
        response.setRoundNo(round.getRoundNo() == null ? 1 : round.getRoundNo());
        response.setUserInput(round.getUserInput());
        response.setResultRenderer(resultRenderer(round));
        if (includeResult) {
            response.setResultText(taskContentService.read(round.getId(), "result", round.getResultText()));
            response.setResultData(round.getResultData());
        }
        response.setStartedAt(round.getStartedAt());
        response.setEndedAt(round.getEndedAt());
        response.setCreatedAt(round.getCreatedAt());
        return response;
    }

    private String resultRenderer(AgentTaskEntity task) {
        return task.getResultRenderer();
    }

    private AgentTaskEntity conversationRoot(AgentTaskEntity task) {
        Long rootId = task.getConversationRootTaskId() == null ? task.getId() : task.getConversationRootTaskId();
        List<AgentTaskEntity> rounds = taskService.conversationRounds(rootId);
        return rounds.isEmpty() ? task : rounds.get(0);
    }

    private String uniqueShareCode() {
        for (int i = 0; i < 10; i++) {
            String code = randomText(CODE_CHARS, 12);
            if (!taskShareRepository.existsByShareCode(code)) {
                return code;
            }
        }
        throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.UNKNOWN_ERROR, "分享码生成失败");
    }

    private String randomText(String chars, int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(chars.charAt(SECURE_RANDOM.nextInt(chars.length())));
        }
        return builder.toString();
    }
}
