package top.fusb.lingxi.repository;

import top.fusb.lingxi.entity.AnalysisPremiseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface AnalysisPremiseRepository extends JpaRepository<AnalysisPremiseEntity, Long>, JpaSpecificationExecutor<AnalysisPremiseEntity> {

    @Override
    Page<AnalysisPremiseEntity> findAll(Specification<AnalysisPremiseEntity> specification, Pageable pageable);

    List<AnalysisPremiseEntity> findAllByEnabledTrueOrderBySortOrderAscUpdatedAtDesc();

    Optional<AnalysisPremiseEntity> findByCode(String code);
}
