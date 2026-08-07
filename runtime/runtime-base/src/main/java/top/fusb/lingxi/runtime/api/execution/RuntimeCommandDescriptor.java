package top.fusb.lingxi.runtime.api.execution;

import top.fusb.lingxi.runtime.api.event.RuntimeActionIcon;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public record RuntimeCommandDescriptor(
        String moduleCode,
        String code,
        String command,
        String name,
        String description,
        String executable,
        List<String> prefixArguments,
        List<RuntimeCommandOutputDescriptor> outputs,
        String guideCode,
        List<RuntimeCommandParameterDescriptor> parameters,
        RuntimeActionIcon icon,
        RuntimeToolExecutionMode executionMode
) {

    public RuntimeCommandDescriptor {
        prefixArguments = prefixArguments == null ? List.of() : List.copyOf(prefixArguments);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        executionMode = executionMode == null ? RuntimeToolExecutionMode.SERIAL : executionMode;
    }

    public RuntimeCommandDescriptor(String moduleCode, String code, String command, String name,
                                    String description, String executable, List<String> prefixArguments,
                                    List<RuntimeCommandOutputDescriptor> outputs) {
        this(moduleCode, code, command, name, description, executable, prefixArguments, outputs,
                null, List.of(), null, RuntimeToolExecutionMode.SERIAL);
    }

    public RuntimeCommandDescriptor(String moduleCode, String code, String command, String name,
                                    String description, String executable, List<String> prefixArguments,
                                    List<RuntimeCommandOutputDescriptor> outputs, String guideCode,
                                    List<RuntimeCommandParameterDescriptor> parameters) {
        this(moduleCode, code, command, name, description, executable, prefixArguments, outputs,
                guideCode, parameters, null, RuntimeToolExecutionMode.SERIAL);
    }

    public RuntimeCommandDescriptor(String moduleCode, String code, String command, String name,
                                    String description, String executable, List<String> prefixArguments,
                                    List<RuntimeCommandOutputDescriptor> outputs, String guideCode,
                                    List<RuntimeCommandParameterDescriptor> parameters, RuntimeActionIcon icon) {
        this(moduleCode, code, command, name, description, executable, prefixArguments, outputs,
                guideCode, parameters, icon, RuntimeToolExecutionMode.SERIAL);
    }

    public Set<String> outputFeatures() {
        return outputs.stream()
                .flatMap(output -> output.features().stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean skillCommand() {
        return moduleCode != null && !moduleCode.isBlank();
    }
}
