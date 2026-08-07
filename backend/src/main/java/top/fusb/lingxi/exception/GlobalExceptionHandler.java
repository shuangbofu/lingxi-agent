package top.fusb.lingxi.exception;

import top.fusb.lingxi.dto.Result;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.LazyInitializationException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e) {
        log.info("业务异常 code={} subCode={} message={}", e.getErrorCode().getCode(), e.getErrorSubCode().getSubCode(), e.getMessage());
        return Result.failure(e.getErrorCode().getCode(), e.getErrorSubCode().getSubCode(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    public Result<Void> handleValidationException(Exception e) {
        log.info("参数校验异常 message={}", e.getMessage());
        return Result.failure(ErrorCode.PARAM_ERROR.getCode(), ErrorSubCode.VALIDATION_FAILED.getSubCode(), ErrorSubCode.VALIDATION_FAILED.getDescription());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.info("上传文件超过大小限制 message={}", e.getMessage());
        return Result.failure(ErrorCode.PARAM_ERROR.getCode(), ErrorSubCode.TASK_ATTACHMENT_INVALID.getSubCode(), "单个附件不能超过 50MB");
    }

    @ExceptionHandler(LazyInitializationException.class)
    public Result<Void> handleLazyInitializationException(LazyInitializationException e) {
        log.error("数据加载异常 message={}", e.getMessage(), e);
        return Result.failure(ErrorCode.SYSTEM_ERROR.getCode(), ErrorSubCode.DATA_LOAD_FAILED.getSubCode(), ErrorSubCode.DATA_LOAD_FAILED.getDescription());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResourceFoundException(NoResourceFoundException e) {
        return Result.failure(ErrorCode.NOT_FOUND.getCode(), ErrorSubCode.DATA_LOAD_FAILED.getSubCode(), "资源不存在");
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsableException(AsyncRequestNotUsableException e) {
        log.info("异步请求连接已不可用 message={}", e.getMessage());
    }

    @ExceptionHandler(IOException.class)
    public Result<Void> handleIOException(IOException e) {
        if (isClientDisconnected(e)) {
            log.info("客户端连接已断开 message={}", e.getMessage());
            return null;
        }
        log.error("IO 异常", e);
        return Result.failure(ErrorCode.SYSTEM_ERROR.getCode(), ErrorSubCode.UNKNOWN_ERROR.getSubCode(), ErrorSubCode.UNKNOWN_ERROR.getDescription());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        if (isClientDisconnected(e)) {
            log.info("客户端连接已断开 message={}", e.getMessage());
            return null;
        }
        log.error("系统异常", e);
        return Result.failure(ErrorCode.SYSTEM_ERROR.getCode(), ErrorSubCode.UNKNOWN_ERROR.getSubCode(), ErrorSubCode.UNKNOWN_ERROR.getDescription());
    }

    private boolean isClientDisconnected(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("Broken pipe")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
