package top.fusb.lingxi.runtime.web;

import top.fusb.lingxi.runtime.capability.dto.AgentRuntimeCapabilityConfigInfo;
import top.fusb.lingxi.runtime.capability.dto.AgentRuntimeCapabilityConfigSummary;
import top.fusb.lingxi.runtime.capability.dto.AgentRuntimeContextPutRequest;
import top.fusb.lingxi.runtime.capability.dto.AgentRuntimeContextResponse;
import top.fusb.lingxi.resource.ResourceCatalogCandidate;
import top.fusb.lingxi.resource.ResourceCatalogInvalidateRequest;
import top.fusb.lingxi.resource.ResourceCatalogUpsertRequest;
import top.fusb.lingxi.resource.ResourceCatalogUpsertResponse;
import top.fusb.lingxi.dto.TaskInteractionRequest;
import top.fusb.lingxi.dto.TaskInteractionResponse;
import top.fusb.lingxi.runtime.capability.AgentRuntimeCapabilityConfigService;
import top.fusb.lingxi.resource.ResourceCatalogService;
import top.fusb.lingxi.task.TaskInteractionService;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent-runtime")
public class AgentRuntimeController {

    private final AgentRuntimeCapabilityConfigService agentRuntimeCapabilityConfigService;
    private final ResourceCatalogService resourceCatalogService;
    private final TaskInteractionService taskInteractionService;

    @GetMapping("/capability-configs")
    public List<AgentRuntimeCapabilityConfigSummary> capabilityConfigs(@RequestParam Long taskId) {
        return agentRuntimeCapabilityConfigService.list(taskId);
    }

    @GetMapping("/capability-configs/{id}")
    public AgentRuntimeCapabilityConfigInfo capabilityConfig(@PathVariable Long id,
                                                            @RequestParam Long taskId) {
        return agentRuntimeCapabilityConfigService.detail(id, taskId);
    }

    @GetMapping("/context")
    public AgentRuntimeContextResponse context(@RequestParam Long taskId) {
        return agentRuntimeCapabilityConfigService.context(taskId);
    }

    @PostMapping("/context")
    public AgentRuntimeContextResponse putContext(@RequestParam Long taskId,
                                                  @RequestBody AgentRuntimeContextPutRequest request) {
        return agentRuntimeCapabilityConfigService.putContext(taskId, request);
    }

    @PostMapping("/resources")
    public ResourceCatalogUpsertResponse putResources(@RequestParam Long taskId,
                                                      @Valid @RequestBody ResourceCatalogUpsertRequest request) {
        return resourceCatalogService.upsert(taskId, request);
    }

    @PostMapping("/resources/invalidate")
    public ResourceCatalogUpsertResponse invalidateResources(@RequestParam Long taskId,
                                                             @Valid @RequestBody ResourceCatalogInvalidateRequest request) {
        return resourceCatalogService.invalidate(taskId, request);
    }

    @GetMapping("/resources/search")
    public List<ResourceCatalogCandidate> searchResources(@RequestParam Long taskId,
                                                           @RequestParam String query,
                                                           @RequestParam(defaultValue = "false") boolean includeRelated) {
        return resourceCatalogService.search(taskId, query, includeRelated);
    }

    @PostMapping("/interactions")
    public TaskInteractionResponse createInteraction(@RequestParam Long taskId,
                                                     @Valid @RequestBody TaskInteractionRequest request) {
        return taskInteractionService.create(taskId, request);
    }

    @GetMapping("/interactions/{id}")
    public TaskInteractionResponse interaction(@RequestParam Long taskId, @PathVariable Long id) {
        return taskInteractionService.detail(taskId, id);
    }

    @GetMapping("/interactions/{id}/wait")
    public TaskInteractionResponse waitInteraction(@RequestParam Long taskId,
                                                   @PathVariable Long id,
                                                   @RequestParam(required = false) Integer timeoutSeconds) {
        return taskInteractionService.waitForAnswer(taskId, id, timeoutSeconds);
    }

}
