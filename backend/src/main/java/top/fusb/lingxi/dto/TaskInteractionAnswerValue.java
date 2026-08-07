package top.fusb.lingxi.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class TaskInteractionAnswerValue {

    @Size(max = 100, message = "交互字段名不能超过 100 个字符")
    private String key;

    @Size(max = 20_000, message = "交互字段值不能超过 20000 个字符")
    private String value;

    @Size(max = 100, message = "交互字段选项不能超过 100 个")
    private List<String> selectedValues;
}
