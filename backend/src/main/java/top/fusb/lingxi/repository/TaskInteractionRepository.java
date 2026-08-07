package top.fusb.lingxi.repository;

import top.fusb.lingxi.entity.TaskInteractionEntity;
import top.fusb.lingxi.enums.TaskInteractionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskInteractionRepository extends JpaRepository<TaskInteractionEntity, Long> {

    List<TaskInteractionEntity> findTop50ByTaskIdOrderByCreatedAtAsc(Long taskId);

    List<TaskInteractionEntity> findByTaskIdAndStatusOrderByCreatedAtAsc(Long taskId, TaskInteractionStatus status);

    boolean existsByIdAndTaskId(Long id, Long taskId);

    @Modifying
    @Query("delete from TaskInteractionEntity interaction where interaction.task.id = :taskId")
    int deleteByTaskId(@Param("taskId") Long taskId);
}
