package top.fusb.lingxi.runtime.web;

import top.fusb.lingxi.config.RequirePermission;
import top.fusb.lingxi.auth.AuthService;
import top.fusb.lingxi.runtime.config.RuntimeConfigRequest;
import top.fusb.lingxi.runtime.config.RuntimeConfigResponse;
import top.fusb.lingxi.runtime.config.RuntimeConfigService;
import top.fusb.lingxi.runtime.config.RuntimeModeResponse;
import top.fusb.lingxi.runtime.config.RuntimeModeService;
import top.fusb.lingxi.runtime.api.maintenance.RuntimeMaintenanceOperation;
import top.fusb.lingxi.runtime.api.maintenance.RuntimeMaintenanceStatus;
import top.fusb.lingxi.runtime.core.AgentRuntimeService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/runtime")
public class RuntimeController {

    private final RuntimeConfigService runtimeConfigService;
    private final RuntimeModeService runtimeModeService;
    private final AgentRuntimeService agentRuntimeService;
    private final AuthService authService;

    @GetMapping
    @RequirePermission("TASK_CREATE")
    public List<RuntimeModeResponse> runtimes(HttpSession session) {
        return runtimeModeService.options(authService.requireUser(session));
    }

    @GetMapping("/config")
    @RequirePermission("SYSTEM_ADMIN")
    public RuntimeConfigResponse config() {
        return runtimeConfigService.detail();
    }

    @PutMapping("/config")
    @RequirePermission("SYSTEM_ADMIN")
    public RuntimeConfigResponse saveConfig(@Valid @RequestBody RuntimeConfigRequest request) {
        return runtimeConfigService.save(request);
    }

    @GetMapping("/{runtimeCode}/maintenance/status")
    @RequirePermission("SYSTEM_ADMIN")
    public RuntimeMaintenanceStatus maintenanceStatus(@PathVariable String runtimeCode) {
        return agentRuntimeService.maintenanceStatus(runtimeCode);
    }

    @PostMapping("/{runtimeCode}/maintenance/install")
    @RequirePermission("SYSTEM_ADMIN")
    public RuntimeMaintenanceOperation install(@PathVariable String runtimeCode) {
        return agentRuntimeService.startInstall(runtimeCode);
    }

    @GetMapping("/{runtimeCode}/maintenance/install/status")
    @RequirePermission("SYSTEM_ADMIN")
    public RuntimeMaintenanceOperation installStatus(@PathVariable String runtimeCode) {
        return agentRuntimeService.installStatus(runtimeCode);
    }

}
