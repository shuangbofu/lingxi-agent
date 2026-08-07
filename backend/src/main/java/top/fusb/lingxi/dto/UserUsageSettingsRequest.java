package top.fusb.lingxi.dto;

import lombok.Data;

@Data
public class UserUsageSettingsRequest {

    private Long dailyTokenLimit;
    private Long weeklyTokenLimit;
    private Long monthlyTokenLimit;
}
