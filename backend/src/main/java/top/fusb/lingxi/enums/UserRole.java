package top.fusb.lingxi.enums;

import top.fusb.lingxi.exception.BizException;
import lombok.Getter;

import java.util.Set;

import static top.fusb.lingxi.enums.ErrorCode.PARAM_ERROR;
import static top.fusb.lingxi.enums.ErrorSubCode.VALIDATION_FAILED;

@Getter
public enum UserRole {

    ADMIN("管理员", Set.of(
            "ANALYSIS_USE",
            "DASHBOARD_ADMIN",
            "ANALYSIS_RECORD_ADMIN",
            "TASK_ADMIN",
            "TASK_READ",
            "TASK_CREATE",
            "TASK_CANCEL",
            "DEFINITION_READ",
            "DEFINITION_ADMIN",
            "CAPABILITY_CONFIG_ADMIN",
            "ANALYSIS_PREMISE_ADMIN",
            "USER_ADMIN",
            "SYSTEM_ADMIN"
    )),
    USER("普通用户", Set.of(
            "ANALYSIS_USE",
            "TASK_READ",
            "TASK_CREATE",
            "TASK_CANCEL",
            "DEFINITION_READ"
    ));

    private final String description;
    private final Set<String> permissions;

    UserRole(String description, Set<String> permissions) {
        this.description = description;
        this.permissions = permissions;
    }

    public static UserRole parse(String value) {
        for (UserRole role : values()) {
            if (role.name().equals(value)) {
                return role;
            }
        }
        throw new BizException(PARAM_ERROR, VALIDATION_FAILED, "用户角色不支持");
    }
}
