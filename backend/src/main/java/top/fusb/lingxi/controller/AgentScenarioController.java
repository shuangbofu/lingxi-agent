package top.fusb.lingxi.controller;

import top.fusb.lingxi.config.RequirePermission;
import top.fusb.lingxi.dto.AgentScenarioResponse;
import top.fusb.lingxi.dto.AgentScenarioReorderRequest;
import top.fusb.lingxi.dto.AgentScenarioStateUpdateRequest;
import top.fusb.lingxi.dto.PageResult;
import top.fusb.lingxi.dto.ScenarioPackageInspectionResponse;
import top.fusb.lingxi.service.AnalysisPremiseService;
import top.fusb.lingxi.service.AgentScenarioService;
import top.fusb.lingxi.service.ScenarioPackageService;
import top.fusb.lingxi.auth.AuthService;
import top.fusb.lingxi.entity.UserEntity;
import jakarta.servlet.http.HttpSession;
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
import java.util.Set;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/scenarios")
public class AgentScenarioController {

    private final AgentScenarioService agentScenarioService;
    private final ScenarioPackageService scenarioPackageService;
    private final AuthService authService;
    private final AnalysisPremiseService analysisPremiseService;

    @GetMapping
    @RequirePermission("DEFINITION_READ")
    public List<AgentScenarioResponse> listEnabled(@RequestParam(required = false) Long premiseId,
                                                   @RequestParam(defaultValue = "false") boolean includeUnavailable,
                                                   HttpSession session) {
        UserEntity user = authService.requireUser(session);
        return agentScenarioService.listEnabled(includeUnavailable
                ? Set.of()
                : analysisPremiseService.visibleScenarioCodes(premiseId, user));
    }

    @GetMapping("/admin")
    @RequirePermission("DEFINITION_ADMIN")
    public List<AgentScenarioResponse> listAll() {
        return agentScenarioService.listAll();
    }

    @GetMapping("/admin/page")
    @RequirePermission("DEFINITION_ADMIN")
    public PageResult<AgentScenarioResponse> pageAll(@RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "12") int size) {
        return agentScenarioService.pageAll(page, size);
    }

    @GetMapping("/{code}")
    @RequirePermission("DEFINITION_READ")
    public AgentScenarioResponse detail(@PathVariable String code) {
        return agentScenarioService.detail(code);
    }

    /**
     * 上传并预检单个场景安装包。
     *
     * @param file ZIP 格式场景安装包
     * @return 场景包预览
     * @throws top.fusb.lingxi.exception.BizException 场景包无效或不可覆盖同编码场景时抛出
     */
    @PostMapping("/packages/inspect")
    @RequirePermission("DEFINITION_ADMIN")
    public ScenarioPackageInspectionResponse inspectPackage(@RequestParam("file") MultipartFile file) {
        return scenarioPackageService.inspect(file);
    }

    /**
     * 安装或更新已经预检的单个场景包。
     *
     * @param stagingToken 预检返回的临时凭证
     * @return 安装或更新后的场景
     * @throws top.fusb.lingxi.exception.BizException 预检失效或安装失败时抛出
     */
    @PostMapping("/packages/{stagingToken}/install")
    @RequirePermission("DEFINITION_ADMIN")
    public AgentScenarioResponse installPackage(@PathVariable String stagingToken) {
        return scenarioPackageService.install(stagingToken);
    }

    /**
     * 卸载外置场景安装包。
     *
     * @param code 场景编码
     * @return 无响应数据
     * @throws top.fusb.lingxi.exception.BizException 场景不可卸载或仍在使用时抛出
     */
    @DeleteMapping("/{code}")
    @RequirePermission("DEFINITION_ADMIN")
    public Void uninstall(@PathVariable String code) {
        scenarioPackageService.uninstall(code);
        return null;
    }

    @PutMapping("/order")
    @RequirePermission("DEFINITION_ADMIN")
    public List<AgentScenarioResponse> reorder(@Valid @RequestBody AgentScenarioReorderRequest request) {
        return agentScenarioService.reorder(request.getCodes());
    }

    @PutMapping("/{code}")
    @RequirePermission("DEFINITION_ADMIN")
    public AgentScenarioResponse update(@PathVariable String code,
                                        @Valid @RequestBody AgentScenarioStateUpdateRequest request) {
        return agentScenarioService.update(code, request);
    }
}
