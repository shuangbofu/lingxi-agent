package top.fusb.lingxi.resource;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ResourceCatalogItemRepository extends JpaRepository<ResourceCatalogItemEntity, Long> {

    @Query("select item from ResourceCatalogItemEntity item "
            + "where item.ownerUserId = :ownerUserId and item.providerCode = :providerCode and item.providerScope = :providerScope "
            + "and item.resourceRef = :resourceRef")
    Optional<ResourceCatalogItemEntity> findItem(@Param("ownerUserId") Long ownerUserId,
                                                 @Param("providerCode") String providerCode,
                                                 @Param("providerScope") String providerScope,
                                                 @Param("resourceRef") String resourceRef);

    List<ResourceCatalogItemEntity> findByOwnerUserIdAndProviderCodeInAndExpiresAtAfterOrderByUpdatedAtDesc(
            Long ownerUserId, Set<String> providerCodes, LocalDateTime expiresAt, Pageable pageable);

    long deleteByOwnerUserIdAndProviderCodeInAndExpiresAtLessThanEqual(
            Long ownerUserId, Set<String> providerCodes, LocalDateTime expiresAt);
}
