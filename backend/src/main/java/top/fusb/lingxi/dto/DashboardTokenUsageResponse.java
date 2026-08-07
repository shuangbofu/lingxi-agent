package top.fusb.lingxi.dto;

import lombok.Data;

import java.util.List;

@Data
public class DashboardTokenUsageResponse {

    private DashboardTokenDimensionResponse summary;

    private ResourceMemoryMetricsResponse resourceMemory;

    private DashboardExecutionMetricsResponse executionExperience;


    private List<DashboardTokenDimensionResponse> byOwners;

    private List<DashboardTokenDimensionResponse> byQuestionTypes;

    private List<DashboardTokenDimensionResponse> byModels;

    private List<DashboardTokenTrendResponse> trends;
}
