package top.fusb.lingxi.runtime.mcp;

import top.fusb.lingxi.dto.PageResult;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.kit.TextKit;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpServerConfig;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpToolPresentation;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpTransport;
import top.fusb.lingxi.runtime.api.model.RuntimeDescriptor;
import top.fusb.lingxi.runtime.core.AgentRuntimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class McpServerService {

    private final McpServerRepository repository;
    private final AgentRuntimeService agentRuntimeService;

    @Transactional(readOnly = true)
    public List<McpServerResponse> list() {
        return repository.findAllByOrderByNameAscCodeAsc().stream().map(this::toResponse).toList();
    }

    /**
     * 分页返回 MCP 服务配置。
     *
     * @param page 页码，从 1 开始
     * @param size 每页数量
     * @return MCP 服务分页结果
     */
    @Transactional(readOnly = true)
    public PageResult<McpServerResponse> page(int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(page - 1, 0), size);
        Page<McpServerEntity> result = repository.findAllByOrderByNameAscCodeAsc(pageRequest);
        return new PageResult<>(result.getContent().stream().map(this::toResponse).toList(),
                result.getTotalElements(), page, size);
    }

    /**
     * 返回已绑定当前 Runtime 且满足资源触发条件的 MCP 配置。
     *
     * @param runtimeCode 当前 Runtime 编码
     * @param availableFeatures 当前任务能力可能产出的资源标签
     * @return 当前任务实际挂载的 MCP 配置
     * @throws BizException Runtime 不存在时抛出
     */
    @Transactional(readOnly = true)
    public List<RuntimeMcpServerConfig> enabledForRuntime(String runtimeCode, Set<String> availableFeatures) {
        RuntimeDescriptor runtime = agentRuntimeService.descriptors().stream()
                .filter(item -> item.code().equals(runtimeCode))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.RUNTIME_UNAVAILABLE,
                        "执行模式不存在：" + runtimeCode));
        if (!runtime.mcpSupported()) {
            return List.of();
        }
        Set<String> features = availableFeatures == null ? Set.of() : Set.copyOf(availableFeatures);
        return repository.findAllByOrderByNameAscCodeAsc().stream()
                .filter(McpServerEntity::isEnabled)
                .filter(entity -> entity.getRuntimeCodes().contains(runtimeCode))
                .filter(entity -> entity.getActivationFeatures().isEmpty()
                        || entity.getActivationFeatures().stream().anyMatch(features::contains))
                .map(this::toRuntimeConfig)
                .toList();
    }

    @Transactional
    public McpServerResponse create(McpServerRequest request) {
        McpServerEntity entity = new McpServerEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setCreatedAt(LocalDateTime.now());
        apply(entity, request, null);
        McpServerEntity saved = repository.save(entity);
        log.info("新增 MCP Server mcpId={} code={} runtimes={}", saved.getId(), saved.getCode(), saved.getRuntimeCodes());
        return toResponse(saved);
    }

    @Transactional
    public McpServerResponse update(String id, McpServerRequest request) {
        McpServerEntity entity = require(id);
        apply(entity, request, id);
        McpServerEntity saved = repository.save(entity);
        log.info("修改 MCP Server mcpId={} code={} enabled={} runtimes={}", id, saved.getCode(),
                saved.isEnabled(), saved.getRuntimeCodes());
        return toResponse(saved);
    }

    @Transactional
    public void delete(String id) {
        McpServerEntity entity = require(id);
        if (entity.isBuiltin()) {
            throw validation("平台内置 MCP 不能删除，可以停用或取消 Runtime 绑定");
        }
        repository.delete(entity);
        log.info("删除 MCP Server mcpId={} code={}", id, entity.getCode());
    }

    private void apply(McpServerEntity entity, McpServerRequest request, String currentId) {
        String requestedCode = TextKit.blankToNull(request.getCode());
        String code = requestedCode == null
                ? currentId == null ? "mcp-" + entity.getId().substring(0, 8) : entity.getCode()
                : requestedCode.trim().toLowerCase(Locale.ROOT);
        if (!code.matches("[a-z][a-z0-9-]{1,79}")) {
            throw validation("MCP 编码只能包含小写字母、数字和连字符");
        }
        repository.findByCode(code)
                .filter(existing -> !existing.getId().equals(currentId))
                .ifPresent(existing -> {
                    throw validation("MCP 编码已经存在：" + code);
                });
        validateConnection(request);
        Set<String> runtimeCodes = normalizeRuntimeCodes(request.getRuntimeCodes());
        entity.setCode(code);
        entity.setName(request.getName().trim());
        entity.setInstructions(TextKit.blankToNull(request.getInstructions()));
        entity.setTransport(request.getTransport());
        entity.setCommand(TextKit.blankToNull(request.getCommand()));
        entity.setArguments(request.getArguments() == null ? List.of() : request.getArguments().stream()
                .map(String::trim).filter(value -> !value.isEmpty()).toList());
        entity.setUrl(TextKit.blankToNull(request.getUrl()));
        entity.setEnvironment(normalizeValues(request.getEnvironment()));
        entity.setHeaders(normalizeValues(request.getHeaders()));
        entity.setRuntimeCodes(new LinkedHashSet<>(runtimeCodes));
        entity.setActivationFeatures(normalizeNames(request.getActivationFeatures()));
        entity.setToolAllowlist(request.getToolAllowlist() == null ? new LinkedHashSet<>()
                : request.getToolAllowlist().stream().map(String::trim).filter(value -> !value.isEmpty())
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        entity.setToolPresentations(normalizeToolPresentations(request.getToolPresentations()));
        entity.setEnabled(request.isEnabled());
        entity.setUpdatedAt(LocalDateTime.now());
    }

    private void validateConnection(McpServerRequest request) {
        if (request.getTransport() == RuntimeMcpTransport.STDIO) {
            if (TextKit.blankToNull(request.getCommand()) == null) {
                throw validation("stdio MCP 必须配置启动命令");
            }
            return;
        }
        String url = TextKit.blankToNull(request.getUrl());
        URI uri;
        try {
            uri = url == null ? null : URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw validation("Streamable HTTP MCP 地址无效");
        }
        if (uri == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw validation("Streamable HTTP MCP 必须配置有效的 http 或 https 地址");
        }
    }

    private Set<String> normalizeRuntimeCodes(Set<String> requested) {
        Set<String> supported = agentRuntimeService.descriptors().stream()
                .filter(RuntimeDescriptor::mcpSupported)
                .map(RuntimeDescriptor::code)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> normalized = requested == null ? Set.of() : requested.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> invalid = new LinkedHashSet<>(normalized);
        invalid.removeAll(supported);
        if (!invalid.isEmpty()) {
            throw validation("以下执行模式不支持 MCP：" + String.join("、", invalid));
        }
        return normalized;
    }

    private Map<String, String> normalizeValues(Map<String, String> source) {
        Map<String, String> result = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null) {
                    result.put(key.trim(), value);
                }
            });
        }
        return result;
    }

    private LinkedHashSet<String> normalizeNames(Set<String> source) {
        if (source == null) {
            return new LinkedHashSet<>();
        }
        return source.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<String, RuntimeMcpToolPresentation> normalizeToolPresentations(
            Map<String, RuntimeMcpToolPresentation> source) {
        Map<String, RuntimeMcpToolPresentation> result = new LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        source.forEach((toolName, presentation) -> {
            String normalizedName = TextKit.blankToNull(toolName);
            String label = presentation == null ? null : TextKit.blankToNull(presentation.label());
            if (normalizedName != null && label != null) {
                if (presentation.icon() == null) {
                    throw validation("MCP 工具展示必须配置图标：" + normalizedName.trim());
                }
                result.put(normalizedName.trim(), new RuntimeMcpToolPresentation(
                        label.trim(), presentation.icon(), presentation.executionMode()));
            }
        });
        return result;
    }

    private McpServerEntity require(String id) {
        return repository.findById(id).orElseThrow(() -> validation("MCP Server 不存在"));
    }

    private McpServerResponse toResponse(McpServerEntity entity) {
        McpServerResponse response = new McpServerResponse();
        response.setId(entity.getId());
        response.setCode(entity.getCode());
        response.setName(entity.getName());
        response.setInstructions(entity.getInstructions());
        response.setTransport(entity.getTransport());
        response.setCommand(entity.getCommand());
        response.setArguments(List.copyOf(entity.getArguments()));
        response.setUrl(entity.getUrl());
        response.setEnvironment(Map.copyOf(entity.getEnvironment()));
        response.setHeaders(Map.copyOf(entity.getHeaders()));
        response.setRuntimeCodes(Set.copyOf(entity.getRuntimeCodes()));
        response.setActivationFeatures(Set.copyOf(entity.getActivationFeatures()));
        response.setToolAllowlist(Set.copyOf(entity.getToolAllowlist()));
        response.setToolPresentations(entity.getToolPresentations() == null
                ? Map.of() : Map.copyOf(entity.getToolPresentations()));
        response.setEnabled(entity.isEnabled());
        response.setBuiltin(entity.isBuiltin());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        return response;
    }

    private RuntimeMcpServerConfig toRuntimeConfig(McpServerEntity entity) {
        return new RuntimeMcpServerConfig(entity.getCode(), entity.getName(), entity.getInstructions(), entity.getTransport(),
                entity.getCommand(), entity.getArguments(), entity.getUrl(), entity.getEnvironment(),
                entity.getHeaders(), entity.getActivationFeatures(), entity.getToolAllowlist(),
                entity.getToolPresentations());
    }

    private BizException validation(String message) {
        return new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, message);
    }
}
