package top.fusb.lingxi.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class TaskContinueRequest {

    @Size(max = 50_000, message = "输入内容不能超过 50000 个字符")
    private String userInput;

    @Size(max = 6, message = "每次最多上传 5 个普通附件和 1 个全文附件")
    private List<String> attachmentIds;
}
