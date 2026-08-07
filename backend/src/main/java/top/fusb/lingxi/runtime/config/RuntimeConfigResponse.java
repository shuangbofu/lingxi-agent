package top.fusb.lingxi.runtime.config;

import lombok.Data;

import java.time.LocalDateTime;
@Data
public class RuntimeConfigResponse {

    private Integer maxTaskConcurrency;

    private Long globalDailyTokenLimit;

    private Long globalWeeklyTokenLimit;

    private Long globalMonthlyTokenLimit;

    private String globalBoundaryPrompt;

    private LocalDateTime updatedAt;
}
