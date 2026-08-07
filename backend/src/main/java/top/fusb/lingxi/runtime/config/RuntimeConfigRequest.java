package top.fusb.lingxi.runtime.config;

import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class RuntimeConfigRequest {

    private Integer maxTaskConcurrency;

    @Positive(message = "全局每日 Token 配额必须大于 0")
    private Long globalDailyTokenLimit;

    @Positive(message = "全局每周 Token 配额必须大于 0")
    private Long globalWeeklyTokenLimit;

    @Positive(message = "全局每月 Token 配额必须大于 0")
    private Long globalMonthlyTokenLimit;

    private String globalBoundaryPrompt;
}
