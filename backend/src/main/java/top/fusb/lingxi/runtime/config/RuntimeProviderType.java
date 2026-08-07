package top.fusb.lingxi.runtime.config;

import top.fusb.lingxi.runtime.api.model.RuntimeModelProtocol;

import java.util.List;

/**
 * JSON 目录中声明的平台供应商类型及模型调用能力。
 *
 * @param value 供应商类型键
 * @param label 显示名称
 * @param icon 浅色主题品牌图片路径
 * @param darkIcon 深色主题品牌图片路径
 * @param defaultBaseUrl 默认服务地址
 * @param defaultProtocol 默认模型协议
 * @param supportedProtocols 允许使用的模型协议
 * @param imageInputSupported 是否支持图片输入
 * @param thinkingFieldName Chat Completions 返回思考内容时使用的字段名
 * @param reasoningEffortOptions 可选推理强度
 */
public record RuntimeProviderType(
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
}
