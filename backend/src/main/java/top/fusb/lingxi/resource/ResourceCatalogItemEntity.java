package top.fusb.lingxi.resource;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Entity
@Table(name = "resource_catalog_item", uniqueConstraints = {
        @UniqueConstraint(name = "uk_resource_catalog_item", columnNames = {"owner_user_id", "provider_code", "provider_scope", "resource_ref"})
})
public class ResourceCatalogItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "provider_code", nullable = false, length = 100)
    private String providerCode;

    @Column(name = "provider_scope", nullable = false, length = 120)
    private String providerScope;

    @Column(name = "source_config_id")
    private Long sourceConfigId;

    @Column(name = "resource_ref", nullable = false, length = 300)
    private String resourceRef;

    @Column(name = "resource_kind", nullable = false, length = 100)
    private String resourceKind;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(length = 2000)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> aliases = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> labels = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> relations = List.of();

    @Column(name = "search_text", length = 5000)
    private String searchText;

    @Column(length = 200)
    private String revision;

    @Column(name = "source_task_id")
    private Long sourceTaskId;

    @Column(nullable = false)
    private LocalDateTime observedAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
