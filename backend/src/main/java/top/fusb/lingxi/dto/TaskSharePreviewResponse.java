package top.fusb.lingxi.dto;

import top.fusb.lingxi.enums.TaskStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class TaskSharePreviewResponse {

    private String shareCode;
    private Long taskId;
    private String sharedByUsername;
    private String sharedByDisplayName;
    private String scenarioName;
    private String scenario;
    private String resultRenderer;
    private TaskStatus status;
    private String title;
    private String userInput;
    private Integer sharedRoundNo;
    private String sharedRoundUserInput;
    private String resultText;
    private TaskResultData resultData;
    private List<TaskShareRoundPreviewResponse> rounds = List.of();
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime createdAt;
}
