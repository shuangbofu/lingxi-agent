package top.fusb.lingxi.runtime.core;

import top.fusb.lingxi.runtime.api.AgentRuntime;
import top.fusb.lingxi.runtime.api.model.RuntimeDescriptor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AgentRuntimeRegistry {

    private final Map<String, AgentRuntime> runtimes;

    public AgentRuntimeRegistry(List<AgentRuntime> runtimes) {
        Map<String, AgentRuntime> registered = new LinkedHashMap<>();
        for (AgentRuntime runtime : runtimes) {
            String code = normalize(runtime.code());
            AgentRuntime previous = registered.putIfAbsent(code, runtime);
            if (previous != null) {
                throw new IllegalStateException("Agent runtime code duplicated: " + code);
            }
        }
        this.runtimes = Map.copyOf(registered);
    }

    /**
     * 根据运行时编码取得实现。
     *
     * @param runtimeCode 运行时编码
     * @return 已注册运行时
     * @throws IllegalStateException 运行时未注册时抛出
     */
    public AgentRuntime require(String runtimeCode) {
        String code = normalize(runtimeCode);
        AgentRuntime runtime = runtimes.get(code);
        if (runtime == null) {
            throw new IllegalStateException("Agent runtime is not registered: " + code);
        }
        return runtime;
    }

    public List<String> codes() {
        return runtimes.keySet().stream().sorted().toList();
    }

    public List<RuntimeDescriptor> descriptors() {
        return runtimes.values().stream()
                .map(AgentRuntime::descriptor)
                .sorted(java.util.Comparator.comparing(RuntimeDescriptor::code))
                .toList();
    }

    /**
     * 根据 Runtime 自身声明的优先级选择平台默认实现。
     *
     * @return 默认 Runtime 编码
     * @throws IllegalStateException 没有注册任何 Runtime 时抛出
     */
    public String defaultCode() {
        return runtimes.values().stream()
                .map(AgentRuntime::descriptor)
                .max(java.util.Comparator.comparingInt(RuntimeDescriptor::defaultPriority)
                        .thenComparing(RuntimeDescriptor::code, java.util.Comparator.reverseOrder()))
                .map(RuntimeDescriptor::code)
                .orElseThrow(() -> new IllegalStateException("No agent runtime is registered"));
    }

    private String normalize(String runtimeCode) {
        if (runtimeCode == null || runtimeCode.isBlank()) {
            throw new IllegalArgumentException("Agent runtime code must not be blank");
        }
        return runtimeCode.trim().toLowerCase(Locale.ROOT);
    }
}
