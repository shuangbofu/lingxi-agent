package top.fusb.lingxi.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserResponse {

    private Long id;
    private String username;
    private String displayName;
    private String avatarUrl;
    private String role;
    private String roleName;
    private Long dailyTokenLimit;
    private Long weeklyTokenLimit;
    private Long monthlyTokenLimit;
    private boolean enabled;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
