package top.fusb.lingxi.enums;

import top.fusb.lingxi.exception.BizException;

import static top.fusb.lingxi.enums.ErrorCode.PARAM_ERROR;
import static top.fusb.lingxi.enums.ErrorSubCode.VALIDATION_FAILED;

public enum TaskStatus {
    PENDING,
    RUNNING,
    WAITING_USER,
    SUCCESS,
    FAILED,
    CANCELED;

    /**
     * 将前端传入的任务状态转换为枚举。
     *
     * @param value 任务状态编码
     * @return 任务状态枚举
     * @throws BizException 状态编码不支持时抛出
     */
    public static TaskStatus parse(String value) {
        for (TaskStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new BizException(PARAM_ERROR, VALIDATION_FAILED, "任务状态不支持");
    }
}
