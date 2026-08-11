package top.fusb.lingxi.controller;

import top.fusb.lingxi.config.RequirePermission;
import top.fusb.lingxi.dto.AgentCapabilityResponse;
import top.fusb.lingxi.dto.AgentCapabilityStateUpdateRequest;
import top.fusb.lingxi.dto.CapabilityPackageInspectionResponse;
import top.fusb.lingxi.dto.PageResult;
import top.fusb.lingxi.service.AgentCapabilityService;
import top.fusb.lingxi.service.CapabilityPackageService;
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
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/capabilities")
public class AgentCapabilityController {

    private final AgentCapabilityService agentCapabilityService;
    private final CapabilityPackageService capabilityPackageService;

    @GetMapping("/admin")
    @RequirePermission("DEFINITION_ADMIN")
    public List<AgentCapabilityResponse> listAll() {
        return agentCapabilityService.listAll();
    }

    @GetMapping("/admin/page")
    @RequirePermission("DEFINITION_ADMIN")
    public PageResult<AgentCapabilityResponse> pageAll(@RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "12") int size) {
        return agentCapabilityService.pageAll(page, size);
    }

    @GetMapping("/{code}")
    @RequirePermission("DEFINITION_ADMIN")
    public AgentCapabilityResponse detail(@PathVariable String code) {
        return agentCapabilityService.detail(code);
    }

    @PutMapping("/{code}")
    @RequirePermission("DEFINITION_ADMIN")
    public AgentCapabilityResponse update(@PathVariable String code,
                                          @Valid @RequestBody AgentCapabilityStateUpdateRequest request) {
        return agentCapabilityService.update(code, request);
    }

    @PostMapping("/packages/inspect")
    @RequirePermission("DEFINITION_ADMIN")
    public CapabilityPackageInspectionResponse inspectPackage(@RequestParam("file") MultipartFile file) {
        return capabilityPackageService.inspect(file);
    }

    @PostMapping("/packages/{stagingToken}/install")
    @RequirePermission("DEFINITION_ADMIN")
    public AgentCapabilityResponse installPackage(@PathVariable String stagingToken) {
        return capabilityPackageService.install(stagingToken);
    }

    /**
     * 卸载外置 Skill 安装包。
     *
     * @param code Skill 编码
     * @return 无响应数据
     * @throws top.fusb.lingxi.exception.BizException Skill 不可卸载或仍在使用时抛出
     */
    @DeleteMapping("/{code}")
    @RequirePermission("DEFINITION_ADMIN")
    public Void uninstall(@PathVariable String code) {
        capabilityPackageService.uninstall(code);
        return null;
    }
}
