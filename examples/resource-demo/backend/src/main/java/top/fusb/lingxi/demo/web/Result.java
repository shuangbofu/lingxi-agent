package top.fusb.lingxi.demo.web;

public record Result<T>(String code, String subcode, String message, T data) {

    public static <T> Result<T> success(T data) {
        return new Result<>("SUCCESS", null, null, data);
    }

    public static <T> Result<T> error(ErrorCode errorCode, String message) {
        return new Result<>("BUSINESS_ERROR", errorCode.name(), message, null);
    }
}
