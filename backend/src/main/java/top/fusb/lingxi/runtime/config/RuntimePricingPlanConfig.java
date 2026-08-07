package top.fusb.lingxi.runtime.config;

import top.fusb.lingxi.runtime.api.model.RuntimeModelPricing;
import lombok.Data;

@Data
public class RuntimePricingPlanConfig {

    private String id;
    private String name;
    private String providerId;
    private String modelProfileId;
    private RuntimeModelPricing pricing;
    private Integer sortOrder;
}
