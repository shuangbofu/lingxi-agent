package top.fusb.lingxi.runtime.api.execution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RuntimeExecutionEnvironment(
        Map<String, String> variables,
        List<String> pathEntries,
        List<String> pythonPathEntries,
        List<String> sensitiveValues,
        List<RuntimeCommandDescriptor> commands,
        List<RuntimeSkillDescriptor> skills,
        List<RuntimeCommandGuideDescriptor> commandGuides
) {

    public RuntimeExecutionEnvironment {
        variables = variables == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(variables));
        pathEntries = pathEntries == null ? List.of() : List.copyOf(pathEntries);
        pythonPathEntries = pythonPathEntries == null ? List.of() : List.copyOf(pythonPathEntries);
        sensitiveValues = sensitiveValues == null ? List.of() : List.copyOf(sensitiveValues);
        commands = commands == null ? List.of() : List.copyOf(commands);
        skills = skills == null ? List.of() : List.copyOf(skills);
        commandGuides = commandGuides == null ? List.of() : List.copyOf(commandGuides);
    }

    public RuntimeExecutionEnvironment(Map<String, String> variables, List<String> pathEntries,
                                       List<String> pythonPathEntries, List<String> sensitiveValues,
                                       List<RuntimeCommandDescriptor> commands, List<RuntimeSkillDescriptor> skills) {
        this(variables, pathEntries, pythonPathEntries, sensitiveValues, commands, skills, List.of());
    }

    public static RuntimeExecutionEnvironment empty() {
        return new RuntimeExecutionEnvironment(Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public RuntimeExecutionEnvironment withSensitiveValue(String value) {
        if (value == null || value.isBlank()) {
            return this;
        }
        List<String> values = new ArrayList<>(sensitiveValues);
        values.add(value.trim());
        return new RuntimeExecutionEnvironment(variables, pathEntries, pythonPathEntries, values, commands, skills,
                commandGuides);
    }
}
