package top.fusb.lingxi.controller;

import top.fusb.lingxi.auth.AuthService;
import top.fusb.lingxi.config.RequirePermission;
import top.fusb.lingxi.dto.AnalysisPremiseContextParameterResponse;
import top.fusb.lingxi.dto.AnalysisPremiseOptionResponse;
import top.fusb.lingxi.dto.AnalysisPremiseResponse;
import top.fusb.lingxi.dto.AnalysisPremiseSaveRequest;
import top.fusb.lingxi.dto.PageResult;
import top.fusb.lingxi.service.AnalysisPremiseService;
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

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/analysis-premises")
public class AnalysisPremiseController {

    private final AnalysisPremiseService analysisPremiseService;
    private final AuthService authService;

    @GetMapping("/available")
    @RequirePermission("TASK_CREATE")
    public List<AnalysisPremiseOptionResponse> available(HttpSession session) {
        return analysisPremiseService.availableOptions(authService.requireUser(session));
    }

    @GetMapping("/page")
    @RequirePermission("ANALYSIS_PREMISE_ADMIN")
    public PageResult<AnalysisPremiseResponse> page(@RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "10") int size,
                                                    @RequestParam(required = false) String query) {
        return analysisPremiseService.page(page, size, query);
    }

    @GetMapping("/context-parameters")
    @RequirePermission("ANALYSIS_PREMISE_ADMIN")
    public List<AnalysisPremiseContextParameterResponse> contextParameters() {
        return analysisPremiseService.contextParameters();
    }

    @PostMapping
    @RequirePermission("ANALYSIS_PREMISE_ADMIN")
    public AnalysisPremiseResponse create(@Valid @RequestBody AnalysisPremiseSaveRequest request) {
        return analysisPremiseService.create(request);
    }

    @PutMapping("/{id}")
    @RequirePermission("ANALYSIS_PREMISE_ADMIN")
    public AnalysisPremiseResponse update(@PathVariable Long id, @Valid @RequestBody AnalysisPremiseSaveRequest request) {
        return analysisPremiseService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @RequirePermission("ANALYSIS_PREMISE_ADMIN")
    public Void delete(@PathVariable Long id) {
        analysisPremiseService.delete(id);
        return null;
    }
}
