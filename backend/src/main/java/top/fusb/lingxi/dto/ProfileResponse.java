package top.fusb.lingxi.dto;

import lombok.Data;

@Data
public class ProfileResponse {

    private Long id;
    private String username;
    private String displayName;
    private String avatarUrl;
    private String role;
    private String roleName;
    private UserUsageQuotaResponse usageQuota;
}
