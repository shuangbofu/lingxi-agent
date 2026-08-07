package top.fusb.lingxi.repository;

import top.fusb.lingxi.entity.TaskRuntimeContextValueEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskRuntimeContextValueRepository extends JpaRepository<TaskRuntimeContextValueEntity, Long> {

    List<TaskRuntimeContextValueEntity> findByTaskIdOrderByContextKeyAsc(Long taskId);

    Optional<TaskRuntimeContextValueEntity> findByTaskIdAndContextKey(Long taskId, String contextKey);

    int deleteByTaskId(Long taskId);
}
