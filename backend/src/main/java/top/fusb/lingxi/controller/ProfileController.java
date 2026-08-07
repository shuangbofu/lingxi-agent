package top.fusb.lingxi.controller;

import top.fusb.lingxi.auth.ProfileService;
import top.fusb.lingxi.dto.DashboardTokenUsageResponse;
import top.fusb.lingxi.dto.ProfileResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;

    @GetMapping
    public ProfileResponse detail(HttpSession session) {
        return profileService.detail(session);
    }

    @GetMapping("/usage")
    public DashboardTokenUsageResponse usage(@RequestParam(required = false) String createdStart,
                                             @RequestParam(required = false) String createdEnd,
                                             HttpSession session) {
        return profileService.usage(createdStart, createdEnd, session);
    }
}
