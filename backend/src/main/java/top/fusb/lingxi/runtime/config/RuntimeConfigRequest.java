package top.fusb.lingxi.runtime.config;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class RuntimeConfigRequest {

    private Integer maxTaskConcurrency;

    @Min(value = 5, message = "任务最长执行时间不能少于 5 分钟")
    @Max(value = 1440, message = "任务最长执行时间不能超过 1440 分钟")
    private Integer taskExecutionTimeoutMinutes;

    @Positive(message = "全局每日 Token 配额必须大于 0")
    private Long globalDailyTokenLimit;

    @Positive(message = "全局每周 Token 配额必须大于 0")
    private Long globalWeeklyTokenLimit;

    @Positive(message = "全局每月 Token 配额必须大于 0")
    private Long globalMonthlyTokenLimit;

    private String globalBoundaryPrompt;
}
