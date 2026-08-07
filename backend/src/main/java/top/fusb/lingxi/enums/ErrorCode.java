package top.fusb.lingxi.enums;

import lombok.Getter;

@Getter
public enum ErrorCode {

    PARAM_ERROR("PARAM_ERROR", "参数错误"),
    AUTH_ERROR("AUTH_ERROR", "认证异常"),
    NOT_FOUND("NOT_FOUND", "数据不存在"),
    SYSTEM_ERROR("SYSTEM_ERROR", "系统异常"),
    PROCESS_ERROR("PROCESS_ERROR", "进程执行异常"),
    TASK_ERROR("TASK_ERROR", "任务异常");

    private final String code;
    private final String description;

    ErrorCode(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
