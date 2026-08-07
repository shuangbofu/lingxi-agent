package top.fusb.lingxi.controller;

import top.fusb.lingxi.config.RequirePermission;
import top.fusb.lingxi.dto.DashboardTokenUsageResponse;
import top.fusb.lingxi.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/dashboard")
@RequirePermission("DASHBOARD_ADMIN")
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/token-usage")
    public DashboardTokenUsageResponse tokenUsage(@RequestParam(required = false) String createdStart,
                                                  @RequestParam(required = false) String createdEnd,
                                                  @RequestParam(required = false) Long ownerId,
                                                  @RequestParam(required = false) String scenario,
                                                  @RequestParam(required = false) String modelProfileId,
                                                  @RequestParam(required = false) String runtimeCode,
                                                  @RequestParam(required = false) String granularity) {
        return dashboardService.tokenUsage(
                createdStart, createdEnd, ownerId, scenario, modelProfileId, runtimeCode, granularity);
    }
}
