package top.fusb.lingxi.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import top.fusb.lingxi.entity.AgentCapabilityEntity;

import java.util.List;
import java.util.Set;

public interface AgentCapabilityRepository extends JpaRepository<AgentCapabilityEntity, String> {

    List<AgentCapabilityEntity> findAllByOrderByCodeAsc();

    Page<AgentCapabilityEntity> findAllByOrderByCodeAsc(Pageable pageable);

    List<AgentCapabilityEntity> findAllByCodeIn(Set<String> codes);
}
