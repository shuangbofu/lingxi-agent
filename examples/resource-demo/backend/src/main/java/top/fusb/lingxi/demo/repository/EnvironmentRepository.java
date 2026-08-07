package top.fusb.lingxi.demo.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import top.fusb.lingxi.demo.domain.EnvironmentEntity;

import java.util.List;

public interface EnvironmentRepository extends JpaRepository<EnvironmentEntity, Long> {

    List<EnvironmentEntity> findByProjectIdOrderByNameAsc(Long projectId);

    void deleteByProjectId(Long projectId);
}
