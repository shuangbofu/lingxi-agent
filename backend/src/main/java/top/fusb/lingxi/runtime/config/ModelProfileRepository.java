package top.fusb.lingxi.runtime.config;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelProfileRepository extends JpaRepository<ModelProfileEntity, String> {

    List<ModelProfileEntity> findByOwnerIsNullOrderBySortOrderAscNameAsc();

    List<ModelProfileEntity> findByOwnerIdOrderBySortOrderAscNameAsc(Long ownerId);

    Optional<ModelProfileEntity> findByIdAndOwnerIsNull(String id);

    Optional<ModelProfileEntity> findByIdAndOwnerId(String id, Long ownerId);

    boolean existsByProviderId(String providerId);

}
