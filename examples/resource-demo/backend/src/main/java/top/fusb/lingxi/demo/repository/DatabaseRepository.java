package top.fusb.lingxi.demo.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import top.fusb.lingxi.demo.domain.DatabaseEntity;

import java.util.List;

public interface DatabaseRepository extends JpaRepository<DatabaseEntity, Long> {

    List<DatabaseEntity> findByProjectIdOrderByNameAsc(Long projectId);

    List<DatabaseEntity> findByEnvironmentIdOrderByNameAsc(Long environmentId);

    void deleteByProjectId(Long projectId);
}
