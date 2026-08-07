package top.fusb.lingxi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    private boolean success;
    private String code;
    private String subCode;
    private String message;
    private T data;

    public static <T> Result<T> success(T data) {
        return new Result<>(true, "SUCCESS", "SUCCESS", "成功", data);
    }

    public static <T> Result<T> failure(String code, String subCode, String message) {
        return new Result<>(false, code, subCode, message, null);
    }
}
