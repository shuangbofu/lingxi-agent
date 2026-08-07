package top.fusb.lingxi.runtime.config;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelProviderRepository extends JpaRepository<ModelProviderEntity, String> {

    List<ModelProviderEntity> findByOwnerIsNullOrderBySortOrderAscNameAsc();

    List<ModelProviderEntity> findByOwnerIdOrderBySortOrderAscNameAsc(Long ownerId);

    Optional<ModelProviderEntity> findByIdAndOwnerIsNull(String id);

    Optional<ModelProviderEntity> findByIdAndOwnerId(String id, Long ownerId);
}
