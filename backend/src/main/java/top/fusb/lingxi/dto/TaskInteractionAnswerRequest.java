package top.fusb.lingxi.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class TaskInteractionAnswerRequest {

    @Size(max = 100, message = "交互动作不能超过 100 个字符")
    private String action;

    @Size(max = 20_000, message = "交互内容不能超过 20000 个字符")
    private String answerText;

    @Size(max = 100, message = "交互选项不能超过 100 个")
    private List<String> selectedValues;

    @Valid
    @Size(max = 50, message = "交互字段不能超过 50 个")
    private List<TaskInteractionAnswerValue> answerValues;
}
