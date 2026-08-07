package top.fusb.lingxi.dto;

import lombok.Data;

@Data
public class UserUsageQuotaPeriodResponse {

    private Long tokenLimit;

    private Long tokenUsed;

    private Long tokenRemaining;

    private Double usagePercent;

    private boolean limited;

    private boolean exceeded;

    private boolean inherited;
}
