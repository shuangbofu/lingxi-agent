package top.fusb.lingxi.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import top.fusb.lingxi.entity.CapabilityConfigEntity;

import java.util.List;

public interface CapabilityConfigRepository extends JpaRepository<CapabilityConfigEntity, Long> {

    List<CapabilityConfigEntity> findAllByOrderByUpdatedAtDesc();

    List<CapabilityConfigEntity> findByCapabilityCodeOrderByUpdatedAtDesc(String capabilityCode);

    Page<CapabilityConfigEntity> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    Page<CapabilityConfigEntity> findByCapabilityCodeOrderByUpdatedAtDesc(String capabilityCode, Pageable pageable);

    List<CapabilityConfigEntity> findAllByEnabledTrueOrderByUpdatedAtDesc();
}
