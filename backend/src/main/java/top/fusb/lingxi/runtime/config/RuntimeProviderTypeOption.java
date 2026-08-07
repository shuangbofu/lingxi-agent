package top.fusb.lingxi.runtime.config;

import top.fusb.lingxi.runtime.api.model.RuntimeModelProtocol;

import java.util.List;

/**
 * 提供给管理端选择的供应商类型定义。
 *
 * @param value 供应商类型值
 * @param label 显示名称
 * @param icon 浅色主题品牌图片路径
 * @param darkIcon 深色主题品牌图片路径
 * @param defaultBaseUrl 默认服务地址
 * @param defaultProtocol 新增模型时使用的默认协议
 * @param supportedProtocols 允许使用的模型协议
 * @param imageInputSupported 是否支持图片输入
 * @param thinkingFieldName Chat Completions 返回思考内容时使用的字段名
 * @param reasoningEffortOptions 可选推理强度
 */
public record RuntimeProviderTypeOption(
        String value,
        String label,
        String icon,
        String darkIcon,
        String defaultBaseUrl,
        RuntimeModelProtocol defaultProtocol,
        List<RuntimeModelProtocol> supportedProtocols,
        boolean imageInputSupported,
        String thinkingFieldName,
        List<RuntimeReasoningEffortOption> reasoningEffortOptions
) {

    /**
     * 从供应商类型创建对外选项。
     *
     * @param type 平台供应商类型
     * @return 可供前端展示和自动填充的类型定义
     */
    public static RuntimeProviderTypeOption from(RuntimeProviderType type) {
        return new RuntimeProviderTypeOption(
                type.value(), type.label(), type.icon(), type.darkIcon(), type.defaultBaseUrl(),
                type.defaultProtocol(), type.supportedProtocols(), type.imageInputSupported(),
                type.thinkingFieldName(), type.reasoningEffortOptions());
    }
}
