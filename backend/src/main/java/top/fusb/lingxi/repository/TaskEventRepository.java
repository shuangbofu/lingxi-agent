package top.fusb.lingxi.repository;

import top.fusb.lingxi.entity.TaskEventEntity;
import top.fusb.lingxi.enums.TaskEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface TaskEventRepository extends JpaRepository<TaskEventEntity, Long> {

    List<TaskEventEntity> findTop200ByTaskIdAndTypeNotOrderByCreatedAtAsc(Long taskId, String excludedType);

    List<TaskEventEntity> findTop200ByTaskIdAndIdGreaterThanAndTypeNotOrderByCreatedAtAsc(Long taskId, Long id, String excludedType);

    List<TaskEventEntity> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    List<TaskEventEntity> findByTask_IdInOrderByTask_IdAscCreatedAtAsc(Set<Long> taskIds);

    boolean existsByIdAndTaskId(Long id, Long taskId);

    boolean existsByTaskIdAndStatusAndTitleStartingWith(Long taskId, TaskEventStatus status, String titlePrefix);

    @Modifying
    @Query("delete from TaskEventEntity event where event.task.id = :taskId")
    int deleteByTaskId(@Param("taskId") Long taskId);
}
