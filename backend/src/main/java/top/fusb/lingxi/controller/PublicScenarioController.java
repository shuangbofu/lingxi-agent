package top.fusb.lingxi.controller;

import top.fusb.lingxi.dto.PublicLoginResponse;
import top.fusb.lingxi.dto.PublicScenarioResponse;
import top.fusb.lingxi.auth.AuthService;
import top.fusb.lingxi.service.AgentScenarioService;
import top.fusb.lingxi.service.LoginDemoDefinitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/public")
public class PublicScenarioController {

    private final AgentScenarioService agentScenarioService;
    private final LoginDemoDefinitionService loginDemoDefinitionService;
    private final AuthService authService;

    @GetMapping("/scenarios")
    public List<PublicScenarioResponse> list() {
        return agentScenarioService.listPublicPreviews();
    }

    @GetMapping("/login")
    public PublicLoginResponse login() {
        return new PublicLoginResponse(
                agentScenarioService.listPublicPreviews(),
                loginDemoDefinitionService.get(),
                authService.isInitializationRequired());
    }
}
