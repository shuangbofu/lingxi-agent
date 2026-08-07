package top.fusb.lingxi.controller;

import top.fusb.lingxi.dto.AuthSessionResponse;
import top.fusb.lingxi.dto.InitialAdminSetupRequest;
import top.fusb.lingxi.dto.LoginRequest;
import top.fusb.lingxi.dto.PasswordChangeRequest;
import top.fusb.lingxi.auth.AuthService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/setup")
    public AuthSessionResponse setup(@Valid @RequestBody InitialAdminSetupRequest request, HttpSession session) {
        return authService.setupInitialAdmin(request, session);
    }

    @PostMapping("/login")
    public AuthSessionResponse login(@Valid @RequestBody LoginRequest request, HttpSession session) {
        return authService.login(request, session);
    }

    @PostMapping("/logout")
    public Void logout(HttpSession session) {
        authService.logout(session);
        return null;
    }

    @PostMapping("/password")
    public Void changePassword(@Valid @RequestBody PasswordChangeRequest request, HttpSession session) {
        authService.changePassword(request, session);
        return null;
    }

    @GetMapping("/me")
    public AuthSessionResponse me(HttpSession session) {
        return authService.current(session);
    }
}
