package top.fusb.lingxi.dto;

import lombok.Data;

import java.util.List;
import java.util.Set;

@Data
public class AuthSessionResponse {

    private boolean authenticated;
    private Long id;
    private String username;
    private String displayName;
    private String avatarUrl;
    private String role;
    private String roleName;
    private Set<String> permissions;
    private List<MenuItemResponse> menus;
}
