package top.fusb.lingxi.controller;

import top.fusb.lingxi.config.RequirePermission;
import top.fusb.lingxi.auth.AuthService;
import jakarta.servlet.http.HttpSession;
import top.fusb.lingxi.dto.ModelEndpointCheckRequest;
import top.fusb.lingxi.dto.ModelEndpointCheckResponse;
import top.fusb.lingxi.runtime.web.ModelConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/model")
@RequirePermission("TASK_CREATE")
public class ModelController {

    private final ModelConfigService modelConfigService;
    private final AuthService authService;

    @PostMapping("/check")
    public ModelEndpointCheckResponse check(@RequestBody ModelEndpointCheckRequest request, HttpSession session) {
        return modelConfigService.check(request, authService.requireUser(session));
    }
}
