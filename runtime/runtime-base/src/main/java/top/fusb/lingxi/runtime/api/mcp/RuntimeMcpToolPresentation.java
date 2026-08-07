package top.fusb.lingxi.runtime.api.mcp;

import top.fusb.lingxi.runtime.api.event.RuntimeActionIcon;
import top.fusb.lingxi.runtime.api.execution.RuntimeToolExecutionMode;

/**
 * MCP Tool 的平台展示元数据。
 *
 * @param label 面向用户的动作名称
 * @param icon 与具体前端图标库无关的图标语义
 * @param executionMode 显式工具执行语义；未配置时由 MCP readOnlyHint 决定，默认串行
 */
public record RuntimeMcpToolPresentation(
        String label,
        RuntimeActionIcon icon,
        RuntimeToolExecutionMode executionMode
) {

    public RuntimeMcpToolPresentation(String label, RuntimeActionIcon icon) {
        this(label, icon, null);
    }
}
