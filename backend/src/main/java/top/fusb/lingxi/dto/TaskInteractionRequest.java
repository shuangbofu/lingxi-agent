package top.fusb.lingxi.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class TaskInteractionRequest {

    @Size(max = 2_000, message = "交互问题不能超过 2000 个字符")
    private String question;

    @Size(max = 20_000, message = "交互内容不能超过 20000 个字符")
    private String content;

    @Size(max = 30, message = "交互类型不能超过 30 个字符")
    private String inputType;

    @Valid
    @Size(max = 300, message = "交互选项不能超过 300 个")
    private List<TaskInteractionOption> options;

    @Valid
    @Size(max = 20, message = "交互动作不能超过 20 个")
    private List<TaskInteractionAction> actions;

    @Valid
    @Size(max = 50, message = "交互字段不能超过 50 个")
    private List<TaskInteractionField> fields;

    private Boolean required;

    @Size(max = 500, message = "占位提示不能超过 500 个字符")
    private String placeholder;

    @Size(max = 2_000, message = "回答提示不能超过 2000 个字符")
    private String answerHint;

    @Size(max = 20_000, message = "默认值不能超过 20000 个字符")
    private String defaultValue;

    @Size(max = 100, message = "上下文键不能超过 100 个字符")
    private String contextKey;

    private Integer timeoutSeconds;
}
