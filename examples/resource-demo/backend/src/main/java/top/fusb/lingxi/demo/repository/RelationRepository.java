package top.fusb.lingxi.demo.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import top.fusb.lingxi.demo.domain.RelationEntity;

import java.util.List;

public interface RelationRepository extends JpaRepository<RelationEntity, Long> {

    List<RelationEntity> findByProjectIdOrderByIdAsc(Long projectId);

    void deleteByProjectId(Long projectId);
}
