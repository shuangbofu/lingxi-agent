package top.fusb.lingxi.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TaskEventResponse {

    private Long id;
    private String type;
    private String status;
    private String title;
    private String detail;
    private Boolean detailFile;
    private TaskEventPayload payload;
    private LocalDateTime createdAt;
}
