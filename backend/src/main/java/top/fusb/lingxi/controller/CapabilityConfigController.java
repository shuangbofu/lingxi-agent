package top.fusb.lingxi.controller;

import top.fusb.lingxi.config.RequirePermission;
import top.fusb.lingxi.dto.CapabilityConfigResponse;
import top.fusb.lingxi.dto.CapabilityConfigSaveRequest;
import top.fusb.lingxi.dto.PageResult;
import top.fusb.lingxi.service.CapabilityConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/capability-configs")
@RequirePermission("CAPABILITY_CONFIG_ADMIN")
public class CapabilityConfigController {

    private final CapabilityConfigService capabilityConfigService;

    @GetMapping("/page")
    public PageResult<CapabilityConfigResponse> page(@RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "10") int size,
                                                  @RequestParam(required = false) String capabilityCode) {
        return capabilityConfigService.page(page, size, capabilityCode);
    }

    @PostMapping
    public CapabilityConfigResponse create(@Valid @RequestBody CapabilityConfigSaveRequest request) {
        return capabilityConfigService.create(request);
    }

    @PutMapping("/{id}")
    public CapabilityConfigResponse update(@PathVariable Long id, @Valid @RequestBody CapabilityConfigSaveRequest request) {
        return capabilityConfigService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public Void delete(@PathVariable Long id) {
        capabilityConfigService.delete(id);
        return null;
    }
}
