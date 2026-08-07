package top.fusb.lingxi.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TaskResumeRequest {

    @Size(max = 50_000, message = "输入内容不能超过 50000 个字符")
    private String userInput;
}
