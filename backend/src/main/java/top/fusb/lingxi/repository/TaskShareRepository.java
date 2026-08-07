package top.fusb.lingxi.repository;

import top.fusb.lingxi.entity.TaskShareEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TaskShareRepository extends JpaRepository<TaskShareEntity, Long> {

    boolean existsByShareCode(String shareCode);

    @EntityGraph(attributePaths = {"task", "task.owner", "createdBy", "sharedRounds"})
    Optional<TaskShareEntity> findFirstByTaskIdOrderByCreatedAtAsc(Long taskId);

    @EntityGraph(attributePaths = {"task", "task.owner", "createdBy", "sharedRounds"})
    Optional<TaskShareEntity> findWithTaskByShareCode(String shareCode);
}
