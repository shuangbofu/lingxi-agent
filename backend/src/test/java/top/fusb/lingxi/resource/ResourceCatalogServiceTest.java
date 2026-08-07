package top.fusb.lingxi.resource;

import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.entity.CapabilityConfigEntity;
import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.enums.TaskEventStatus;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.repository.AgentTaskRepository;
import top.fusb.lingxi.repository.TaskEventRepository;
import top.fusb.lingxi.service.CapabilityConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceCatalogServiceTest {

    private ResourceCatalogItemRepository resourceCatalogItemRepository;
    private AgentTaskRepository agentTaskRepository;
    private CapabilityConfigService capabilityConfigService;
    private TaskEventRepository taskEventRepository;
    private ResourceCatalogService service;

    @BeforeEach
    void setUp() {
        resourceCatalogItemRepository = mock(ResourceCatalogItemRepository.class);
        agentTaskRepository = mock(AgentTaskRepository.class);
        capabilityConfigService = mock(CapabilityConfigService.class);
        taskEventRepository = mock(TaskEventRepository.class);
        when(taskEventRepository.existsByTaskIdAndStatusAndTitleStartingWith(
                any(), eq(TaskEventStatus.SUCCESS), any())).thenReturn(true);
        service = new ResourceCatalogService(resourceCatalogItemRepository, agentTaskRepository,
                capabilityConfigService, taskEventRepository);
    }

    @Test
    void shouldPersistAgentSummarizedResourceForMountedProvider() {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(9L);
        task.setOwner(user(5L));
        when(agentTaskRepository.findWithDetailsById(9L)).thenReturn(Optional.of(task));
        when(agentTaskRepository.findTaskCapabilityCodesByTaskId(9L)).thenReturn(Set.of("project-hub"));
        when(resourceCatalogItemRepository.findItem(5L, "project-hub", "module", "project:12"))
                .thenReturn(Optional.empty());
        ResourceCatalogEntryRequest entry = entry("project:12", "project", "示例应用甲");
        entry.setDescription("token=should-not-be-cached");
        ResourceCatalogUpsertRequest request = new ResourceCatalogUpsertRequest();
        request.setProviderCode("project-hub");
        request.setEntries(List.of(entry));

        ResourceCatalogUpsertResponse response = service.upsert(9L, request);

        assertEquals(1, response.getAcceptedCount());
        ArgumentCaptor<ResourceCatalogItemEntity> captor = ArgumentCaptor.forClass(ResourceCatalogItemEntity.class);
        verify(resourceCatalogItemRepository).save(captor.capture());
        assertEquals("示例应用甲", captor.getValue().getName());
        assertEquals("token=[REDACTED]", captor.getValue().getDescription());
        assertEquals(9L, captor.getValue().getSourceTaskId());
        verify(agentTaskRepository).recordResourceMemorySave(9L, 1, 0);
    }

    @Test
    void shouldRejectMemoryForProviderNotMountedByTask() {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(9L);
        task.setOwner(user(5L));
        when(agentTaskRepository.findWithDetailsById(9L)).thenReturn(Optional.of(task));
        when(agentTaskRepository.findTaskCapabilityCodesByTaskId(9L)).thenReturn(Set.of("project-hub"));
        ResourceCatalogUpsertRequest request = new ResourceCatalogUpsertRequest();
        request.setProviderCode("other-capability");
        request.setEntries(List.of(entry("resource:1", "resource", "其他资源")));

        assertThrows(BizException.class, () -> service.upsert(9L, request));
    }

    @Test
    void shouldRejectMemoryWithoutSuccessfulProviderCall() {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(9L);
        task.setOwner(user(5L));
        when(agentTaskRepository.findWithDetailsById(9L)).thenReturn(Optional.of(task));
        when(agentTaskRepository.findTaskCapabilityCodesByTaskId(9L)).thenReturn(Set.of("project-hub"));
        when(taskEventRepository.existsByTaskIdAndStatusAndTitleStartingWith(
                9L, TaskEventStatus.SUCCESS, "capability:project-hub:")).thenReturn(false);
        ResourceCatalogUpsertRequest request = new ResourceCatalogUpsertRequest();
        request.setProviderCode("project-hub");
        request.setEntries(List.of(entry("project:12", "project", "示例应用甲")));

        assertThrows(BizException.class, () -> service.upsert(9L, request));
    }

    @Test
    void shouldRejectAmbiguousProviderConfiguration() {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(9L);
        task.setOwner(user(5L));
        when(agentTaskRepository.findWithDetailsById(9L)).thenReturn(Optional.of(task));
        when(agentTaskRepository.findTaskCapabilityCodesByTaskId(9L)).thenReturn(Set.of("project-hub"));
        when(capabilityConfigService.listEnabledConfigs()).thenReturn(List.of(
                providerConfig(1L, "project-hub"), providerConfig(2L, "project-hub")));
        ResourceCatalogUpsertRequest request = new ResourceCatalogUpsertRequest();
        request.setProviderCode("project-hub");
        request.setEntries(List.of(entry("project:12", "project", "示例应用甲")));

        assertThrows(BizException.class, () -> service.upsert(9L, request));
    }

    @Test
    void shouldRankRelevantCachedResources() {
        ResourceCatalogItemEntity relevant = item("project-hub", "project:12", "project", "示例应用甲",
                List.of("服务甲"), List.of("UAT"), "sample-app-a");
        ResourceCatalogItemEntity unrelated = item("project-hub", "project:18", "project", "示例应用乙",
                List.of("服务乙"), List.of("PROD"), "sample-app-b");
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(9L);
        task.setOwner(user(5L));
        when(agentTaskRepository.findWithDetailsById(9L)).thenReturn(Optional.of(task));
        when(agentTaskRepository.findTaskCapabilityCodesByTaskId(9L)).thenReturn(Set.of("project-hub"));
        when(resourceCatalogItemRepository.findByOwnerUserIdAndProviderCodeInAndExpiresAtAfterOrderByUpdatedAtDesc(
                eq(5L), eq(Set.of("project-hub")), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(unrelated, relevant));
        when(resourceCatalogItemRepository.deleteByOwnerUserIdAndProviderCodeInAndExpiresAtLessThanEqual(
                eq(5L), eq(Set.of("project-hub")), any(LocalDateTime.class))).thenReturn(2L);

        List<ResourceCatalogCandidate> result = service.search(9L, "查一下服务甲 UAT 日志");

        assertEquals(1, result.size());
        assertEquals("project:12", result.get(0).getResourceRef());
        assertEquals(ResourceCatalogMatchType.IDENTITY, result.get(0).getMatchType());
        assertEquals(List.of("aliases", "labels"), result.get(0).getMatchedFields());
        verify(agentTaskRepository).recordResourceMemorySearch(9L, 1, 1, 2, 0);
    }

    @Test
    void shouldNotTreatTopicOverlapAsResourceIdentity() {
        ResourceCatalogItemEntity sampleApplication = item("project-hub", "app:5", "project", "示例应用甲",
                List.of(), List.of("UAT"), "主题甲 场景甲 操作甲");
        sampleApplication.setDescription("用于验证主题匹配的示例应用");
        sampleApplication.setSourceTaskId(52L);
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(9L);
        task.setOwner(user(5L));
        when(agentTaskRepository.findWithDetailsById(9L)).thenReturn(Optional.of(task));
        when(agentTaskRepository.findTaskCapabilityCodesByTaskId(9L)).thenReturn(Set.of("project-hub"));
        when(resourceCatalogItemRepository.findByOwnerUserIdAndProviderCodeInAndExpiresAtAfterOrderByUpdatedAtDesc(
                eq(5L), eq(Set.of("project-hub")), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(sampleApplication));

        List<ResourceCatalogCandidate> defaultResult = service.search(9L, "UAT 主题甲有哪些场景甲");
        List<ResourceCatalogCandidate> discoveryResult = service.search(
                9L, "UAT 主题甲有哪些场景甲", true);

        assertEquals(List.of(), defaultResult);
        assertEquals(1, discoveryResult.size());
        assertEquals(ResourceCatalogMatchType.RELATED, discoveryResult.get(0).getMatchType());
        assertEquals(List.of("labels", "searchText"), discoveryResult.get(0).getMatchedFields());
        assertEquals(52L, discoveryResult.get(0).getSourceTaskId());
    }

    @Test
    void shouldReuseResourceWhenStableNameIsPresent() {
        ResourceCatalogItemEntity sampleApplication = item("project-hub", "app:5", "project", "示例应用甲",
                List.of("sample-app-a"), List.of("UAT"), "示例服务");
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(9L);
        task.setOwner(user(5L));
        when(agentTaskRepository.findWithDetailsById(9L)).thenReturn(Optional.of(task));
        when(agentTaskRepository.findTaskCapabilityCodesByTaskId(9L)).thenReturn(Set.of("project-hub"));
        when(resourceCatalogItemRepository.findByOwnerUserIdAndProviderCodeInAndExpiresAtAfterOrderByUpdatedAtDesc(
                eq(5L), eq(Set.of("project-hub")), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(sampleApplication));

        List<ResourceCatalogCandidate> result = service.search(9L, "检查示例应用甲 UAT 的部署配置");

        assertEquals(1, result.size());
        assertEquals(ResourceCatalogMatchType.IDENTITY, result.get(0).getMatchType());
        assertEquals("app:5", result.get(0).getResourceRef());
        assertEquals(List.of("name", "labels"), result.get(0).getMatchedFields());
    }

    @Test
    void shouldInvalidateOnlyResourcesOwnedByMountedProviderScope() {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(9L);
        task.setOwner(user(5L));
        ResourceCatalogItemEntity stale = item("project-hub", "project:12", "project", "示例旧应用",
                List.of(), List.of(), "示例旧应用");
        when(agentTaskRepository.findWithDetailsById(9L)).thenReturn(Optional.of(task));
        when(agentTaskRepository.findTaskCapabilityCodesByTaskId(9L)).thenReturn(Set.of("project-hub"));
        when(resourceCatalogItemRepository.findItem(5L, "project-hub", "module", "project:12"))
                .thenReturn(Optional.of(stale));
        ResourceCatalogInvalidateRequest request = new ResourceCatalogInvalidateRequest();
        request.setProviderCode("project-hub");
        request.setRefs(List.of("project:12", "project:missing"));

        ResourceCatalogUpsertResponse response = service.invalidate(9L, request);

        assertEquals(1, response.getAcceptedCount());
        verify(resourceCatalogItemRepository).deleteAll(List.of(stale));
        verify(agentTaskRepository).recordResourceMemoryInvalidation(9L, 1);
    }

    private UserEntity user(Long id) {
        UserEntity user = new UserEntity();
        user.setId(id);
        return user;
    }

    private CapabilityConfigEntity providerConfig(Long id, String providerCode) {
        CapabilityConfigEntity config = new CapabilityConfigEntity();
        config.setId(id);
        config.setCapabilityCode(providerCode);
        return config;
    }

    private ResourceCatalogEntryRequest entry(String ref, String kind, String name) {
        ResourceCatalogEntryRequest entry = new ResourceCatalogEntryRequest();
        entry.setRef(ref);
        entry.setKind(kind);
        entry.setName(name);
        return entry;
    }

    private ResourceCatalogItemEntity item(String providerCode, String ref, String kind, String name,
                                           List<String> aliases, List<String> labels, String searchText) {
        ResourceCatalogItemEntity item = new ResourceCatalogItemEntity();
        item.setProviderCode(providerCode);
        item.setResourceRef(ref);
        item.setResourceKind(kind);
        item.setName(name);
        item.setAliases(aliases);
        item.setLabels(labels);
        item.setRelations(List.of());
        item.setSearchText(searchText);
        item.setObservedAt(LocalDateTime.now());
        return item;
    }
}
