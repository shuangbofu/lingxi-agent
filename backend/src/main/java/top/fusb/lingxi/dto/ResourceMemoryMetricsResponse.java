package top.fusb.lingxi.dto;

import lombok.Data;

@Data
public class ResourceMemoryMetricsResponse {

    private Long searchCount = 0L;
    private Long hitCount = 0L;
    private Long candidateCount = 0L;
    private Long saveCount = 0L;
    private Long createdCount = 0L;
    private Long refreshedCount = 0L;
    private Long expiredCount = 0L;
    private Long invalidatedCount = 0L;
    private Long estimatedSavedDiscoveryCalls = 0L;
}
