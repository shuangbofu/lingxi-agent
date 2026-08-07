package top.fusb.lingxi.runtime.api.execution;

import java.util.Objects;

/**
 * Backend 为一次 Agent 执行准备的工作区目录布局。
 *
 * @param executionRoot Runtime 进程工作目录
 * @param taskContextRoot Backend 管理的任务上下文目录
 * @param runtimeInputRoot 能力命令临时输入目录
 * @param artifactsRoot 用户可见产物目录
 * @param runtimeRoot Backend 管理的 Runtime 数据根目录
 * @param runtimeStateRoot 当前 Runtime 独占的持久状态目录
 * @param privateRuntimeRoot 平台私有运行证据目录
 */
public record RuntimeWorkspaceLayout(
        String executionRoot,
        String taskContextRoot,
        String runtimeInputRoot,
        String artifactsRoot,
        String runtimeRoot,
        String runtimeStateRoot,
        String privateRuntimeRoot
) {

    public RuntimeWorkspaceLayout {
        executionRoot = requirePath(executionRoot, "executionRoot");
        taskContextRoot = requirePath(taskContextRoot, "taskContextRoot");
        runtimeInputRoot = requirePath(runtimeInputRoot, "runtimeInputRoot");
        artifactsRoot = requirePath(artifactsRoot, "artifactsRoot");
        runtimeRoot = requirePath(runtimeRoot, "runtimeRoot");
        runtimeStateRoot = requirePath(runtimeStateRoot, "runtimeStateRoot");
        privateRuntimeRoot = requirePath(privateRuntimeRoot, "privateRuntimeRoot");
    }

    private static String requirePath(String value, String field) {
        String path = Objects.requireNonNull(value, field + " 不能为空").trim();
        if (path.isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return path;
    }
}
