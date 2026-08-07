package top.fusb.lingxi.resource;

import top.fusb.lingxi.entity.AgentTaskEntity;
import top.fusb.lingxi.entity.CapabilityConfigEntity;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.enums.TaskEventStatus;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.kit.TextKit;
import top.fusb.lingxi.kit.SensitiveTextKit;
import top.fusb.lingxi.repository.AgentTaskRepository;
import top.fusb.lingxi.repository.TaskEventRepository;
import top.fusb.lingxi.service.CapabilityConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceCatalogService {

    private static final int MAX_SEARCH_ITEMS = 2000;
    private static final int MAX_CANDIDATES = 8;
    private static final int MAX_LIST_VALUE_LENGTH = 300;
    private static final int CACHE_TTL_DAYS = 30;

    private final ResourceCatalogItemRepository resourceCatalogItemRepository;
    private final AgentTaskRepository agentTaskRepository;
    private final CapabilityConfigService capabilityConfigService;
    private final TaskEventRepository taskEventRepository;

    /**
     * 接收已挂载能力在执行过程中发现的资源摘要，并写入跨任务资源目录。
     *
     * @param taskId 当前运行任务 ID
     * @param request 能力提供者及资源摘要
     * @return 本次成功写入的资源数量
     * @throws BizException 任务、能力、服务配置或资源摘要不合法时抛出
     */
    @Transactional
    public ResourceCatalogUpsertResponse upsert(Long taskId, ResourceCatalogUpsertRequest request) {
        AgentTaskEntity task = agentTaskRepository.findWithDetailsById(taskId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.TASK_NOT_FOUND));
        String providerCode = TextKit.blankToNull(request == null ? null : request.getProviderCode());
        Set<String> allowedCodes = agentTaskRepository.findTaskCapabilityCodesByTaskId(taskId);
        if (providerCode == null || !allowedCodes.contains(providerCode)) {
            throw new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.FORBIDDEN, "当前任务未挂载该资源提供能力");
        }
        if (request.getEntries() == null || request.getEntries().isEmpty()) {
            return new ResourceCatalogUpsertResponse(0);
        }
        requireObservedProvider(taskId, providerCode);
        Long sourceConfigId = resolveSourceConfigId(providerCode, request.getSourceConfigId());
        String providerScope = sourceConfigId == null ? "module" : "config:" + sourceConfigId;
        Long ownerUserId = task.getOwner() == null || task.getOwner().getId() == null ? 0L : task.getOwner().getId();
        LocalDateTime now = LocalDateTime.now();
        int acceptedCount = 0;
        int createdCount = 0;
        int refreshedCount = 0;
        for (ResourceCatalogEntryRequest entry : request.getEntries()) {
            if (entry == null || TextKit.blankToNull(entry.getRef()) == null
                    || TextKit.blankToNull(entry.getKind()) == null || TextKit.blankToNull(entry.getName()) == null) {
                throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "资源目录条目缺少必要字段");
            }
            ResourceCatalogItemEntity entity = resourceCatalogItemRepository
                    .findItem(ownerUserId, providerCode, providerScope, entry.getRef().trim())
                    .orElseGet(ResourceCatalogItemEntity::new);
            if (entity.getId() == null) {
                createdCount++;
                entity.setOwnerUserId(ownerUserId);
                entity.setProviderCode(providerCode);
                entity.setProviderScope(providerScope);
                entity.setSourceConfigId(sourceConfigId);
                entity.setResourceRef(entry.getRef().trim());
                entity.setCreatedAt(now);
            } else {
                refreshedCount++;
            }
            entity.setResourceKind(entry.getKind().trim());
            entity.setName(redact(entry.getName().trim()));
            entity.setDescription(TextKit.blankToNull(redact(entry.getDescription())));
            entity.setAliases(cleanValues(entry.getAliases()));
            entity.setLabels(cleanValues(entry.getLabels()));
            entity.setRelations(cleanValues(entry.getRelations()));
            entity.setSearchText(TextKit.blankToNull(redact(entry.getSearchText())));
            entity.setRevision(TextKit.blankToNull(entry.getRevision()));
            entity.setSourceTaskId(task.getId());
            entity.setObservedAt(now);
            entity.setExpiresAt(now.plusDays(CACHE_TTL_DAYS));
            entity.setUpdatedAt(now);
            resourceCatalogItemRepository.save(entity);
            acceptedCount++;
        }
        agentTaskRepository.recordResourceMemorySave(taskId, createdCount, refreshedCount);
        log.info("能力资源目录已增量更新 taskId={} provider={} sourceConfigId={} count={}",
                taskId, providerCode, sourceConfigId, acceptedCount);
        return new ResourceCatalogUpsertResponse(acceptedCount);
    }

    /**
     * 从当前任务已挂载能力贡献的缓存中召回与任务文本相关的资源候选。
     *
     * @param taskId 当前任务 ID，用于约束用户和已挂载能力范围
     * @param queryText 用户问题、分析情境及已确认上下文组成的检索文本
     * @return 按相关性排序的少量资源候选，没有匹配时返回空列表
     * @throws BizException 任务不存在时抛出
     */
    @Transactional
    public List<ResourceCatalogCandidate> search(Long taskId, String queryText) {
        return search(taskId, queryText, false);
    }

    /**
     * 从资源目录召回候选，并区分稳定身份命中与仅主题相关的历史线索。
     *
     * @param taskId 当前任务 ID，用于约束用户和已挂载能力范围
     * @param queryText 当前任务的资源检索文本
     * @param includeRelated 是否同时返回不能识别资源身份的主题相关线索
     * @return 按相关性排序的资源候选
     * @throws BizException 任务不存在时抛出
     */
    @Transactional
    public List<ResourceCatalogCandidate> search(Long taskId, String queryText, boolean includeRelated) {
        AgentTaskEntity task = agentTaskRepository.findWithDetailsById(taskId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.TASK_NOT_FOUND));
        Set<String> providerCodes = agentTaskRepository.findTaskCapabilityCodesByTaskId(taskId);
        Long ownerUserId = task.getOwner() == null || task.getOwner().getId() == null ? 0L : task.getOwner().getId();
        String query = normalize(queryText);
        if (providerCodes == null || providerCodes.isEmpty() || query.isBlank()) {
            agentTaskRepository.recordResourceMemorySearch(taskId, 0, 0, 0, 0);
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        long expiredCount = resourceCatalogItemRepository
                .deleteByOwnerUserIdAndProviderCodeInAndExpiresAtLessThanEqual(ownerUserId, providerCodes, now);
        List<ResourceCatalogItemEntity> activeItems = resourceCatalogItemRepository
                .findByOwnerUserIdAndProviderCodeInAndExpiresAtAfterOrderByUpdatedAtDesc(
                        ownerUserId, providerCodes, now, PageRequest.of(0, MAX_SEARCH_ITEMS));
        Set<Long> enabledConfigIds = capabilityConfigService.listEnabledConfigs().stream()
                .map(CapabilityConfigEntity::getId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        List<ResourceCatalogItemEntity> invalidItems = activeItems.stream()
                .filter(item -> item.getSourceConfigId() != null && !enabledConfigIds.contains(item.getSourceConfigId()))
                .toList();
        if (!invalidItems.isEmpty()) {
            resourceCatalogItemRepository.deleteAll(invalidItems);
            activeItems = activeItems.stream().filter(item -> !invalidItems.contains(item)).toList();
        }
        List<ResourceCatalogCandidate> candidates = activeItems.stream()
                .map(item -> candidate(item, match(item, query)))
                .filter(candidate -> candidate.getScore() > 0)
                .filter(candidate -> includeRelated || candidate.getMatchType() == ResourceCatalogMatchType.IDENTITY)
                .sorted((left, right) -> Integer.compare(right.getScore(), left.getScore()))
                .limit(MAX_CANDIDATES)
                .toList();
        agentTaskRepository.recordResourceMemorySearch(taskId, candidates.isEmpty() ? 0 : 1,
                candidates.size(), expiredCount, invalidItems.size());
        log.info("能力资源目录检索 taskId={} providers={} hit={} candidates={} expired={} invalidated={}",
                taskId, providerCodes.size(), !candidates.isEmpty(), candidates.size(), expiredCount, invalidItems.size());
        return candidates;
    }

    /**
     * 删除已被能力证据确认失效的资源摘要。
     *
     * @param taskId 当前运行任务 ID
     * @param request 资源提供能力、配置范围和稳定资源引用
     * @return 本次实际失效的资源数量
     * @throws BizException 任务未挂载能力或配置不属于该能力时抛出
     */
    @Transactional
    public ResourceCatalogUpsertResponse invalidate(Long taskId, ResourceCatalogInvalidateRequest request) {
        AgentTaskEntity task = agentTaskRepository.findWithDetailsById(taskId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.TASK_NOT_FOUND));
        String providerCode = TextKit.blankToNull(request == null ? null : request.getProviderCode());
        Set<String> allowedCodes = agentTaskRepository.findTaskCapabilityCodesByTaskId(taskId);
        if (providerCode == null || !allowedCodes.contains(providerCode)) {
            throw new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.FORBIDDEN, "当前任务未挂载该资源提供能力");
        }
        requireObservedProvider(taskId, providerCode);
        Long sourceConfigId = resolveSourceConfigId(providerCode, request.getSourceConfigId());
        String providerScope = sourceConfigId == null ? "module" : "config:" + sourceConfigId;
        Long ownerUserId = task.getOwner() == null || task.getOwner().getId() == null ? 0L : task.getOwner().getId();
        List<ResourceCatalogItemEntity> items = request.getRefs().stream()
                .map(String::trim)
                .distinct()
                .map(ref -> resourceCatalogItemRepository.findItem(ownerUserId, providerCode, providerScope, ref).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        resourceCatalogItemRepository.deleteAll(items);
        agentTaskRepository.recordResourceMemoryInvalidation(taskId, items.size());
        log.info("能力资源目录主动失效 taskId={} provider={} sourceConfigId={} count={}",
                taskId, providerCode, sourceConfigId, items.size());
        return new ResourceCatalogUpsertResponse(items.size());
    }

    private Long resolveSourceConfigId(String providerCode, Long requestedConfigId) {
        if (requestedConfigId == null) {
            List<CapabilityConfigEntity> matches = capabilityConfigService.listEnabledConfigs().stream()
                    .filter(config -> providerCode.equals(config.getCapabilityCode()))
                    .toList();
            if (matches.size() == 1) {
                return matches.get(0).getId();
            }
            if (matches.size() > 1) {
                throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                        "该能力存在多个服务配置，缓存资源时必须指定 sourceConfigId");
            }
            return null;
        }
        CapabilityConfigEntity config = capabilityConfigService.requireEnabledConfig(requestedConfigId);
        if (!providerCode.equals(config.getCapabilityCode())) {
            throw new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.FORBIDDEN, "资源来源配置不属于当前能力");
        }
        return requestedConfigId;
    }

    /**
     * 确认当前任务已经成功调用资源声明的提供能力，避免模型把其他能力的结果写入错误命名空间。
     *
     * @param taskId 当前运行任务 ID
     * @param providerCode 资源声明的提供能力编码
     * @return 无返回值
     * @throws BizException 当前任务没有该能力的成功执行证据时抛出
     */
    private void requireObservedProvider(Long taskId, String providerCode) {
        String actionPrefix = "capability:" + providerCode + ":";
        if (!taskEventRepository.existsByTaskIdAndStatusAndTitleStartingWith(
                taskId, TaskEventStatus.SUCCESS, actionPrefix)) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                    "保存或失效资源前必须先成功调用对应能力：" + providerCode);
        }
    }

    private List<String> cleanValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String text = TextKit.blankToNull(value);
            if (text != null) {
                result.add(TextKit.limit(redact(text), MAX_LIST_VALUE_LENGTH));
            }
        }
        return List.copyOf(result);
    }

    private String redact(String value) {
        return SensitiveTextKit.redact(value, List.of());
    }

    private ResourceCatalogCandidate candidate(ResourceCatalogItemEntity item, ResourceMatch match) {
        return ResourceCatalogCandidate.builder()
                .providerCode(item.getProviderCode())
                .sourceConfigId(item.getSourceConfigId())
                .resourceRef(item.getResourceRef())
                .resourceKind(item.getResourceKind())
                .name(item.getName())
                .description(item.getDescription())
                .labels(item.getLabels())
                .matchType(match.matchType())
                .matchedFields(match.matchedFields())
                .sourceTaskId(item.getSourceTaskId())
                .observedAt(item.getObservedAt())
                .score(match.score())
                .build();
    }

    private ResourceMatch match(ResourceCatalogItemEntity item, String query) {
        int score = 0;
        boolean identityMatch = false;
        LinkedHashSet<String> matchedFields = new LinkedHashSet<>();
        String resourceRef = normalize(item.getResourceRef());
        if (!resourceRef.isBlank() && query.contains(resourceRef)) {
            score += 140;
            identityMatch = true;
            matchedFields.add("resourceRef");
        }
        String name = normalize(item.getName());
        if (!name.isBlank() && query.contains(name)) {
            score += 120;
            identityMatch = true;
            matchedFields.add("name");
        }
        for (String alias : item.getAliases() == null ? List.<String>of() : item.getAliases()) {
            String value = normalize(alias);
            if (!value.isBlank() && query.contains(value)) {
                score += 90;
                identityMatch = true;
                matchedFields.add("aliases");
            }
        }
        for (String label : item.getLabels() == null ? List.<String>of() : item.getLabels()) {
            String value = normalize(label);
            if (!value.isBlank() && query.contains(value)) {
                score += 45;
                matchedFields.add("labels");
            }
        }
        Set<String> queryBigrams = bigrams(query);
        List<List<String>> searchableFields = List.of(
                List.of("name", item.getName() == null ? "" : item.getName()),
                List.of("description", item.getDescription() == null ? "" : item.getDescription()),
                List.of("searchText", item.getSearchText() == null ? "" : item.getSearchText()),
                List.of("relations", String.join(" ", item.getRelations() == null ? List.of() : item.getRelations()))
        );
        for (List<String> field : searchableFields) {
            Set<String> overlap = new LinkedHashSet<>(queryBigrams);
            overlap.retainAll(bigrams(normalize(field.get(1))));
            if (overlap.size() >= 2) {
                score += Math.min(overlap.size() * 3, 60);
                matchedFields.add(field.get(0));
            }
        }
        ResourceCatalogMatchType matchType = identityMatch
                ? ResourceCatalogMatchType.IDENTITY
                : ResourceCatalogMatchType.RELATED;
        return new ResourceMatch(score, matchType, List.copyOf(matchedFields));
    }

    private Set<String> bigrams(String value) {
        String compact = value.replaceAll("[^\\p{L}\\p{N}]", "");
        if (compact.length() < 2) {
            return new LinkedHashSet<>();
        }
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index < compact.length() - 1; index++) {
            result.add(compact.substring(index, index + 2));
        }
        return result;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private record ResourceMatch(int score, ResourceCatalogMatchType matchType, List<String> matchedFields) {
    }

}
