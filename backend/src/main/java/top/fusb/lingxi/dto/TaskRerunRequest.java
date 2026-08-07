package top.fusb.lingxi.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TaskRerunRequest {

    @NotBlank(message = "请选择执行模式")
    private String runtimeCode;

    @NotBlank(message = "请选择模型")
    private String modelProfileId;
}
