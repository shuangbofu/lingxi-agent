package top.fusb.lingxi.runtime.config;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelPricingPlanRepository extends JpaRepository<ModelPricingPlanEntity, String> {

    List<ModelPricingPlanEntity> findByProviderOwnerIsNullOrderBySortOrderAscNameAsc();

    List<ModelPricingPlanEntity> findByProviderOwnerIdOrderBySortOrderAscNameAsc(Long ownerId);

    Optional<ModelPricingPlanEntity> findByIdAndProviderOwnerIsNull(String id);

    Optional<ModelPricingPlanEntity> findByIdAndProviderOwnerId(String id, Long ownerId);

    Optional<ModelPricingPlanEntity> findFirstByModelId(String modelId);

    Optional<ModelPricingPlanEntity> findFirstByProviderIdAndModelIsNull(String providerId);

    boolean existsByProviderIdAndModelIsNull(String providerId);

    void deleteByModelId(String modelId);

    void deleteByProviderId(String providerId);
}
