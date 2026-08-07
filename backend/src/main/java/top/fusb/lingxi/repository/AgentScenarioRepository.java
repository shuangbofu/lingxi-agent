package top.fusb.lingxi.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import top.fusb.lingxi.entity.AgentScenarioEntity;

import java.util.List;

public interface AgentScenarioRepository extends JpaRepository<AgentScenarioEntity, String> {

    @Query("select coalesce(max(s.sortOrder), 0) from AgentScenarioEntity s")
    int findMaxSortOrder();

    List<AgentScenarioEntity> findAllByOrderBySortOrderAsc();

    Page<AgentScenarioEntity> findAllByOrderBySortOrderAsc(Pageable pageable);

    List<AgentScenarioEntity> findAllByEnabledTrueOrderBySortOrderAsc();

    List<AgentScenarioEntity> findAllByEnabledTrueAndUserVisibleTrueOrderBySortOrderAsc();
}
