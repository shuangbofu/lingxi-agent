package top.fusb.lingxi.demo.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import top.fusb.lingxi.demo.domain.RepositoryEntity;

import java.util.List;

public interface DemoRepositoryRepository extends JpaRepository<RepositoryEntity, Long> {

    List<RepositoryEntity> findByProjectIdOrderByPrimaryRepoDescNameAsc(Long projectId);

    List<RepositoryEntity> findByEnvironmentIdOrderByPrimaryRepoDescNameAsc(Long environmentId);

    void deleteByProjectId(Long projectId);
}
