package top.fusb.lingxi.dto;

import lombok.Data;

@Data
public class UserUsageQuotaResponse {

    private UserUsageQuotaPeriodResponse daily;

    private UserUsageQuotaPeriodResponse weekly;

    private UserUsageQuotaPeriodResponse monthly;
}
