package top.fusb.lingxi.demo.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException exception) {
        return Result.error(exception.getErrorCode(), exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class, IllegalArgumentException.class})
    public Result<Void> handleInvalidArgument(Exception exception) {
        return Result.error(ErrorCode.INVALID_ARGUMENT, exception.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public Result<Void> handleConflict(DataIntegrityViolationException exception) {
        return Result.error(ErrorCode.CONFLICT, "编码或路径已经存在");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpected(Exception exception) {
        log.error("Unhandled demo resource service error", exception);
        return Result.error(ErrorCode.INTERNAL_ERROR, "服务处理失败，请查看服务端日志");
    }
}
