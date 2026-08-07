package top.fusb.lingxi.runtime.api.support;

import top.fusb.lingxi.runtime.api.execution.RuntimeExecutionEnvironment;

import java.io.File;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RuntimeEnvironmentKit {

    private RuntimeEnvironmentKit() {
    }

    public static void apply(ProcessBuilder builder, RuntimeExecutionEnvironment environment) {
        if (builder == null || environment == null) {
            return;
        }
        Map<String, String> target = builder.environment();
        target.putAll(environment.variables());
        prepend(target, target.containsKey("Path") ? "Path" : "PATH", environment.pathEntries());
        prepend(target, "PYTHONPATH", environment.pythonPathEntries());
    }

    public static Map<String, String> materialize(RuntimeExecutionEnvironment environment,
                                                  Map<String, String> inherited) {
        if (environment == null) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        result.putAll(environment.variables());
        String pathKey = inherited != null && inherited.containsKey("Path") ? "Path" : "PATH";
        putCombined(result, pathKey, environment.pathEntries(), inherited == null ? null : inherited.get(pathKey));
        putCombined(result, "PYTHONPATH", environment.pythonPathEntries(),
                inherited == null ? null : inherited.get("PYTHONPATH"));
        return Map.copyOf(result);
    }

    private static void prepend(Map<String, String> target, String key, List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        String prefix = String.join(File.pathSeparator, entries);
        String existing = target.getOrDefault(key, "");
        target.put(key, prefix + (existing.isBlank() ? "" : File.pathSeparator + existing));
    }

    private static void putCombined(Map<String, String> target, String key, List<String> entries, String existing) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        String prefix = String.join(File.pathSeparator, entries);
        target.put(key, prefix + (existing == null || existing.isBlank()
                ? "" : File.pathSeparator + existing));
    }
}
