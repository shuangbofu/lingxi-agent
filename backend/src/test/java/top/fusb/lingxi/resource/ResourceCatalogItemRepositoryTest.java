package top.fusb.lingxi.resource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
class ResourceCatalogItemRepositoryTest {

    @Autowired
    private ResourceCatalogItemRepository repository;

    @Test
    void shouldPersistStructuredResourceSummary() {
        LocalDateTime now = LocalDateTime.now();
        ResourceCatalogItemEntity entity = new ResourceCatalogItemEntity();
        entity.setOwnerUserId(5L);
        entity.setProviderCode("project-hub");
        entity.setProviderScope("module");
        entity.setResourceRef("project:12");
        entity.setResourceKind("project");
        entity.setName("示例应用甲");
        entity.setAliases(List.of("服务甲"));
        entity.setLabels(List.of("UAT"));
        entity.setRelations(List.of());
        entity.setObservedAt(now);
        entity.setExpiresAt(now.plusDays(30));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        repository.saveAndFlush(entity);

        ResourceCatalogItemEntity saved = repository.findItem(5L, "project-hub", "module", "project:12").orElseThrow();
        assertEquals(List.of("服务甲"), saved.getAliases());
        assertEquals(List.of("UAT"), saved.getLabels());
    }
}
