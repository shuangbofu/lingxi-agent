package top.fusb.lingxi.runtime.config;

import lombok.Data;

import java.util.List;

@Data
public class ModelCatalogResponse {

    private ModelConfigScope scope;
    private List<RuntimeProviderResponse> providers;
    private List<RuntimeModelProfileResponse> models;
    private List<RuntimePricingPlanConfig> pricingPlans;
    private List<RuntimeProviderTypeOption> providerTypes;
}
