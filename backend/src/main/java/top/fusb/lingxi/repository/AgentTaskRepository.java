package top.fusb.lingxi.repository;

import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.enums.TaskStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface AgentTaskRepository extends JpaRepository<AgentTaskEntity, Long>, JpaSpecificationExecutor<AgentTaskEntity> {

    @Override
    @EntityGraph(attributePaths = {"premise", "owner"})
    Page<AgentTaskEntity> findAll(Specification<AgentTaskEntity> specification, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"premise", "owner"})
    List<AgentTaskEntity> findAll(Specification<AgentTaskEntity> specification);

    @EntityGraph(attributePaths = {"premise", "owner"})
    Page<AgentTaskEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = {"premise", "owner"})
    Page<AgentTaskEntity> findAllByOwnerIdOrderByCreatedAtDesc(Long ownerId, Pageable pageable);

    @EntityGraph(attributePaths = {"premise", "owner"})
    Page<AgentTaskEntity> findAllByStatusOrderByCreatedAtDesc(TaskStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"premise", "owner"})
    List<AgentTaskEntity> findByStatusOrderByCreatedAtAsc(TaskStatus status);

    @EntityGraph(attributePaths = {"premise", "owner"})
    Page<AgentTaskEntity> findAllByOwnerIdAndStatusOrderByCreatedAtDesc(Long ownerId, TaskStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"premise", "owner"})
    Optional<AgentTaskEntity> findWithDetailsById(Long id);

    boolean existsBySourceTaskIdAndScenarioCode(Long sourceTaskId, String scenarioCode);

    boolean existsByScenarioCodeAndStatusIn(String scenarioCode, Collection<TaskStatus> statuses);

    @EntityGraph(attributePaths = {"premise", "owner"})
    Optional<AgentTaskEntity> findFirstBySourceTaskIdAndScenarioCodeOrderByCreatedAtDesc(Long sourceTaskId, String scenarioCode);

    @EntityGraph(attributePaths = {"premise", "owner"})
    List<AgentTaskEntity> findByConversationRootTaskIdOrderByRoundNoAscCreatedAtAsc(Long conversationRootTaskId);

    default Set<String> findTaskCapabilityCodesByTaskId(Long taskId) {
        Optional<AgentTaskEntity> task = findById(taskId);
        return task.map(value -> value.getEnabledCapabilityCodes() == null
                        ? new LinkedHashSet<String>() : new LinkedHashSet<>(value.getEnabledCapabilityCodes()))
                .orElseGet(LinkedHashSet::new);
    }

    @Query("select coalesce(sum(task.totalTokens), 0) from AgentTaskEntity task "
            + "where task.owner.id = :ownerId and task.createdAt >= :createdStart and task.createdAt < :createdEnd")
    Long sumTotalTokensByOwnerAndCreatedAt(@Param("ownerId") Long ownerId,
                                           @Param("createdStart") LocalDateTime createdStart,
                                           @Param("createdEnd") LocalDateTime createdEnd);

    @Query("select distinct task.owner from AgentTaskEntity task where task.owner is not null order by task.owner.createdAt asc")
    java.util.List<UserEntity> findDistinctOwners();

    @Modifying
    @Query("update AgentTaskEntity task set task.status = :targetStatus, task.updatedAt = current_timestamp where task.status = :sourceStatus")
    int updateStatus(TaskStatus sourceStatus, TaskStatus targetStatus);

    @Modifying
    @Query("update AgentTaskEntity root set root.updatedAt = :updatedAt where root.id = :rootId and root.updatedAt < :updatedAt")
    int touchConversationRootUpdatedAt(@Param("rootId") Long rootId, @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 只更新任务输出摘要和活跃时间，避免运行事件写入时重写整行大文本字段。
     *
     * @param taskId 任务 ID
     * @param stdoutText 已文件化过程输出的数据库摘要
     * @param updatedAt 本次输出时间
     * @return 更新的任务行数
     * @throws DataAccessException 数据库更新失败时抛出
     */
    @Modifying
    @Query("update AgentTaskEntity task set task.stdoutText = :stdoutText, task.updatedAt = :updatedAt where task.id = :taskId")
    int updateOutputSummary(@Param("taskId") Long taskId,
                            @Param("stdoutText") String stdoutText,
                            @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 只更新执行中的累计 Token 用量，避免流式用量回调重写任务大文本字段。
     *
     * @param taskId 任务 ID
     * @param requestCount 模型请求次数
     * @param inputTokens 输入 Token 数
     * @param cachedInputTokens 缓存命中的输入 Token 数
     * @param cacheCreationInputTokens 写入缓存的输入 Token 数
     * @param outputTokens 输出 Token 数
     * @param reasoningOutputTokens 推理输出 Token 数
     * @param totalTokens 总 Token 数
     * @param updatedAt 本次用量更新时间
     * @return 更新的任务行数
     * @throws DataAccessException 数据库更新失败时抛出
     */
    @Modifying
    @Query("update AgentTaskEntity task set "
            + "task.requestCount = :requestCount, "
            + "task.inputTokens = :inputTokens, "
            + "task.cachedInputTokens = :cachedInputTokens, "
            + "task.cacheCreationInputTokens = :cacheCreationInputTokens, "
            + "task.outputTokens = :outputTokens, "
            + "task.reasoningOutputTokens = :reasoningOutputTokens, "
            + "task.totalTokens = :totalTokens, "
            + "task.updatedAt = :updatedAt "
            + "where task.id = :taskId")
    int updateRuntimeUsage(@Param("taskId") Long taskId,
                           @Param("requestCount") Long requestCount,
                           @Param("inputTokens") Long inputTokens,
                           @Param("cachedInputTokens") Long cachedInputTokens,
                           @Param("cacheCreationInputTokens") Long cacheCreationInputTokens,
                           @Param("outputTokens") Long outputTokens,
                           @Param("reasoningOutputTokens") Long reasoningOutputTokens,
                           @Param("totalTokens") Long totalTokens,
                           @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 查询任务所属会话根任务 ID，供高频定向更新同步会话排序时间。
     *
     * @param taskId 任务 ID
     * @return 会话根任务 ID；首轮任务或不存在时返回 null
     * @throws DataAccessException 数据库查询失败时抛出
     */
    @Query("select task.conversationRootTaskId from AgentTaskEntity task where task.id = :taskId")
    Long findConversationRootTaskId(@Param("taskId") Long taskId);

    @Modifying
    @Query("update AgentTaskEntity task set "
            + "task.resourceMemorySaveCount = coalesce(task.resourceMemorySaveCount, 0) + 1, "
            + "task.resourceMemoryCreatedCount = coalesce(task.resourceMemoryCreatedCount, 0) + :createdCount, "
            + "task.resourceMemoryRefreshedCount = coalesce(task.resourceMemoryRefreshedCount, 0) + :refreshedCount "
            + "where task.id = :taskId")
    int recordResourceMemorySave(@Param("taskId") Long taskId,
                                 @Param("createdCount") long createdCount,
                                 @Param("refreshedCount") long refreshedCount);

    @Modifying
    @Query("update AgentTaskEntity task set "
            + "task.resourceMemorySearchCount = coalesce(task.resourceMemorySearchCount, 0) + 1, "
            + "task.resourceMemoryHitCount = coalesce(task.resourceMemoryHitCount, 0) + :hitCount, "
            + "task.resourceMemoryCandidateCount = coalesce(task.resourceMemoryCandidateCount, 0) + :candidateCount, "
            + "task.resourceMemoryExpiredCount = coalesce(task.resourceMemoryExpiredCount, 0) + :expiredCount, "
            + "task.resourceMemoryInvalidatedCount = coalesce(task.resourceMemoryInvalidatedCount, 0) + :invalidatedCount "
            + "where task.id = :taskId")
    int recordResourceMemorySearch(@Param("taskId") Long taskId,
                                   @Param("hitCount") long hitCount,
                                   @Param("candidateCount") long candidateCount,
                                   @Param("expiredCount") long expiredCount,
                                   @Param("invalidatedCount") long invalidatedCount);

    @Modifying
    @Query("update AgentTaskEntity task set "
            + "task.resourceMemoryInvalidatedCount = coalesce(task.resourceMemoryInvalidatedCount, 0) + :invalidatedCount "
            + "where task.id = :taskId")
    int recordResourceMemoryInvalidation(@Param("taskId") Long taskId,
                                         @Param("invalidatedCount") long invalidatedCount);
}
