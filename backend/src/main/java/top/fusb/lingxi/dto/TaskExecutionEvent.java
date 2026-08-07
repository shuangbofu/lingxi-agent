package top.fusb.lingxi.dto;

import top.fusb.lingxi.enums.TaskEventStatus;
import top.fusb.lingxi.enums.TaskEventType;
import lombok.Data;

@Data
public class TaskExecutionEvent {

    private TaskEventType type;
    private TaskEventStatus status;
    private String title;
    private String detail;
    private TaskEventPayload payload;

    public String displayText() {
        return title == null || title.isBlank() ? "" : title + System.lineSeparator();
    }
}
