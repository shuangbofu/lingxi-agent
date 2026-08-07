package top.fusb.lingxi.controller;

import top.fusb.lingxi.auth.AuthService;
import top.fusb.lingxi.runtime.config.ModelCatalogResponse;
import top.fusb.lingxi.runtime.config.ModelCatalogService;
import top.fusb.lingxi.runtime.config.ModelConfigScope;
import top.fusb.lingxi.runtime.config.RuntimeModelProfileRequest;
import top.fusb.lingxi.runtime.config.RuntimeModelProfileResponse;
import top.fusb.lingxi.runtime.config.RuntimePricingPlanConfig;
import top.fusb.lingxi.runtime.config.RuntimeProviderRequest;
import top.fusb.lingxi.runtime.config.RuntimeProviderResponse;
import jakarta.servlet.http.HttpSession;
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
@RequestMapping("/api/model-configs")
public class ModelConfigController {

    private final ModelCatalogService modelCatalogService;
    private final AuthService authService;

    @GetMapping
    public ModelCatalogResponse catalog(@RequestParam ModelConfigScope scope, HttpSession session) {
        return modelCatalogService.catalog(scope, authService.requireUser(session));
    }

    @PostMapping("/providers")
    public RuntimeProviderResponse createProvider(@RequestParam ModelConfigScope scope,
                                                  @RequestBody RuntimeProviderRequest request,
                                                  HttpSession session) {
        return modelCatalogService.createProvider(scope, request, authService.requireUser(session));
    }

    @PutMapping("/providers/{id}")
    public RuntimeProviderResponse updateProvider(@RequestParam ModelConfigScope scope,
                                                  @PathVariable String id,
                                                  @RequestBody RuntimeProviderRequest request,
                                                  HttpSession session) {
        return modelCatalogService.updateProvider(scope, id, request, authService.requireUser(session));
    }

    @DeleteMapping("/providers/{id}")
    public Void deleteProvider(@RequestParam ModelConfigScope scope, @PathVariable String id,
                               HttpSession session) {
        modelCatalogService.deleteProvider(scope, id, authService.requireUser(session));
        return null;
    }

    @PostMapping("/models")
    public RuntimeModelProfileResponse createModel(@RequestParam ModelConfigScope scope,
                                                   @RequestBody RuntimeModelProfileRequest request,
                                                   HttpSession session) {
        return modelCatalogService.createModel(scope, request, authService.requireUser(session));
    }

    @PutMapping("/models/{id}")
    public RuntimeModelProfileResponse updateModel(@RequestParam ModelConfigScope scope,
                                                   @PathVariable String id,
                                                   @RequestBody RuntimeModelProfileRequest request,
                                                   HttpSession session) {
        return modelCatalogService.updateModel(scope, id, request, authService.requireUser(session));
    }

    @DeleteMapping("/models/{id}")
    public Void deleteModel(@RequestParam ModelConfigScope scope, @PathVariable String id,
                            HttpSession session) {
        modelCatalogService.deleteModel(scope, id, authService.requireUser(session));
        return null;
    }

    @PostMapping("/pricing-plans")
    public RuntimePricingPlanConfig createPricingPlan(@RequestParam ModelConfigScope scope,
                                                      @RequestBody RuntimePricingPlanConfig request,
                                                      HttpSession session) {
        return modelCatalogService.createPricingPlan(scope, request, authService.requireUser(session));
    }

    @PutMapping("/pricing-plans/{id}")
    public RuntimePricingPlanConfig updatePricingPlan(@RequestParam ModelConfigScope scope,
                                                      @PathVariable String id,
                                                      @RequestBody RuntimePricingPlanConfig request,
                                                      HttpSession session) {
        return modelCatalogService.updatePricingPlan(scope, id, request, authService.requireUser(session));
    }

    @DeleteMapping("/pricing-plans/{id}")
    public Void deletePricingPlan(@RequestParam ModelConfigScope scope, @PathVariable String id,
                                  HttpSession session) {
        modelCatalogService.deletePricingPlan(scope, id, authService.requireUser(session));
        return null;
    }
}
