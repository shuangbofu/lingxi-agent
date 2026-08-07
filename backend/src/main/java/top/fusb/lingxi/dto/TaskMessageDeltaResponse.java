package top.fusb.lingxi.dto;

import top.fusb.lingxi.runtime.api.model.RuntimeMessageDeltaType;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TaskMessageDeltaResponse {

    private String messageId;
    private String delta;
    private String content;
    private RuntimeMessageDeltaType type;
}
