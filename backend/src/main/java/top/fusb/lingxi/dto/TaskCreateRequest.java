package top.fusb.lingxi.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class TaskCreateRequest {

    @NotBlank(message = "请选择执行模式")
    private String runtimeCode;

    @NotBlank(message = "请选择模型")
    private String modelProfileId;

    @NotBlank(message = "请选择场景")
    private String scenarioCode;

    private Long premiseId;

    @Size(max = 50_000, message = "输入内容不能超过 50000 个字符")
    private String userInput;

    @Valid
    @Size(max = 50, message = "输入参数不能超过 50 个")
    private List<TaskInputValue> inputValues;

    @Size(max = 6, message = "每次最多上传 5 个普通附件和 1 个全文附件")
    private List<String> attachmentIds;

    private Long sourceTaskId;

}
