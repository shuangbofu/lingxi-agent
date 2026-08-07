package top.fusb.lingxi.controller;

import top.fusb.lingxi.config.RequirePermission;
import top.fusb.lingxi.dto.PageResult;
import top.fusb.lingxi.dto.UserAvatarUploadResponse;
import top.fusb.lingxi.dto.UserResponse;
import top.fusb.lingxi.dto.UserSaveRequest;
import top.fusb.lingxi.dto.UserUsageSettingsRequest;
import top.fusb.lingxi.dto.UserUsageQuotaResponse;
import top.fusb.lingxi.auth.UserService;
import top.fusb.lingxi.service.UserAvatarService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/api/users")
@RequirePermission("USER_ADMIN")
public class UserController {

    private final UserService userService;
    private final UserAvatarService userAvatarService;

    @GetMapping
    public List<UserResponse> list() {
        return userService.list();
    }

    @GetMapping("/page")
    public PageResult<UserResponse> page(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "10") int size) {
        return userService.page(page, size);
    }

    @PostMapping
    public UserResponse create(@Valid @RequestBody UserSaveRequest request) {
        return userService.create(request);
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable Long id, @Valid @RequestBody UserSaveRequest request) {
        return userService.update(id, request);
    }

    @PutMapping("/{id}/usage-settings")
    public UserResponse updateUsageSettings(@PathVariable Long id,
                                            @Valid @RequestBody UserUsageSettingsRequest request) {
        return userService.updateUsageSettings(id, request);
    }

    @GetMapping("/{id}/usage-quota")
    public UserUsageQuotaResponse usageQuota(@PathVariable Long id) {
        return userService.usageQuota(id);
    }

    @PostMapping("/avatar")
    public UserAvatarUploadResponse uploadAvatar(@RequestParam("file") MultipartFile file) {
        return userAvatarService.upload(file);
    }
}
