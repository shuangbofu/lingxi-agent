package top.fusb.lingxi.runtime.config;

import java.util.Map;

public record RuntimeConcurrencyPolicy(
        int globalLimit,
        Map<String, Integer> providerLimits,
        Map<String, Integer> modelLimits,
        Map<String, String> modelProviderIds
) {

    private static final String UNSCOPED_PROVIDER = "__unscoped_provider__";
    private static final String UNSCOPED_MODEL = "__unscoped_model__";

    public RuntimeConcurrencyPolicy {
        providerLimits = Map.copyOf(providerLimits);
        modelLimits = Map.copyOf(modelLimits);
        modelProviderIds = Map.copyOf(modelProviderIds);
    }

    /**
     * 判断模型任务是否仍有供应商和模型两级执行额度。
     *
     * @param modelProfileId 模型配置 ID
     * @param providerRunningCounts 各供应商当前执行数
     * @param modelRunningCounts 各模型当前执行数
     * @return 两级额度均未用满时返回 true
     */
    public boolean allows(String modelProfileId,
                          Map<String, Integer> providerRunningCounts,
                          Map<String, Integer> modelRunningCounts) {
        String modelId = modelProfileId == null ? UNSCOPED_MODEL : modelProfileId;
        String providerId = modelProviderIds.getOrDefault(modelId, UNSCOPED_PROVIDER);
        int providerLimit = providerLimits.getOrDefault(providerId, globalLimit);
        int modelLimit = modelLimits.getOrDefault(modelId, providerLimit);
        return providerRunningCounts.getOrDefault(providerId, 0) < providerLimit
                && modelRunningCounts.getOrDefault(modelId, 0) < modelLimit;
    }

    /**
     * 记录一个即将开始的任务占用的供应商和模型额度。
     *
     * @param modelProfileId 模型配置 ID
     * @param providerRunningCounts 各供应商当前执行数
     * @param modelRunningCounts 各模型当前执行数
     * @return 无返回值
     */
    public void acquire(String modelProfileId,
                        Map<String, Integer> providerRunningCounts,
                        Map<String, Integer> modelRunningCounts) {
        String modelId = modelProfileId == null ? UNSCOPED_MODEL : modelProfileId;
        String providerId = modelProviderIds.getOrDefault(modelId, UNSCOPED_PROVIDER);
        providerRunningCounts.merge(providerId, 1, Integer::sum);
        modelRunningCounts.merge(modelId, 1, Integer::sum);
    }
}
