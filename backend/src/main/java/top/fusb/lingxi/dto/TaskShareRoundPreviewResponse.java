package top.fusb.lingxi.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TaskShareRoundPreviewResponse {

    private Long taskId;
    private Integer roundNo;
    private String userInput;
    private String resultRenderer;
    private String resultText;
    private TaskResultData resultData;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime createdAt;
}
