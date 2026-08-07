package top.fusb.lingxi.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UserSaveRequest {

    @NotBlank
    private String username;

    @NotBlank
    private String displayName;

    private String avatarUrl;

    private String password;

    @NotBlank
    private String role;

    private Boolean enabled;
}
