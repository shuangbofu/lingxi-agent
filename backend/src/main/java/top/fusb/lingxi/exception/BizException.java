package top.fusb.lingxi.exception;

import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import lombok.Getter;

@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final ErrorSubCode errorSubCode;

    public BizException(ErrorCode errorCode, ErrorSubCode errorSubCode) {
        super(errorSubCode.getDescription());
        this.errorCode = errorCode;
        this.errorSubCode = errorSubCode;
    }

    public BizException(ErrorCode errorCode, ErrorSubCode errorSubCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.errorSubCode = errorSubCode;
    }
}
