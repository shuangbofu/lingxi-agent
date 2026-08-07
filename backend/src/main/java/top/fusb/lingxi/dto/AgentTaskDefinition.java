package top.fusb.lingxi.dto;

import top.fusb.lingxi.definition.CapabilityActivationCondition;
import top.fusb.lingxi.definition.ModuleDefinition;
import top.fusb.lingxi.definition.ModuleParameterDefinition;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
public class AgentTaskDefinition {

    private String code;
    private String name;
    private String description;
    private String scenario;
    private String icon;
    private String color;
    private String promptText;
    private Set<String> capabilities = new LinkedHashSet<>();
    private Map<String, Set<String>> capabilityCommands = new LinkedHashMap<>();
    private Map<String, CapabilityActivationCondition> capabilityConditions = new LinkedHashMap<>();
    private Set<String> presentations = new LinkedHashSet<>();
    private List<DefinitionParameter> parameters = List.of();
    private List<DefinitionGuide> guides = List.of();
    private boolean uniqueBySource = false;
    private String resultFormat;
    private String resultRenderer;
    private Integer queuePriority;

    public static AgentTaskDefinition fromModule(ModuleDefinition module, String promptText, List<DefinitionGuide> guides) {
        AgentTaskDefinition definition = base(module.getCode(), module.getName(), module.getDescription(), module.getScenario(),
                promptText, module.getCapabilities());
        definition.setIcon(module.getIcon());
        definition.setColor(module.getColor());
        definition.setUniqueBySource(Boolean.TRUE.equals(module.getUniqueBySource()));
        definition.setResultFormat(module.getResultFormat());
        definition.setResultRenderer(module.getResultRenderer());
        definition.setQueuePriority(module.getQueuePriority());
        definition.setCapabilityCommands(copyCapabilityCommands(module.getCapabilityCommands()));
        definition.setCapabilityConditions(copyCapabilityConditions(module.getCapabilityConditions()));
        definition.setPresentations(module.getPresentations() == null ? new LinkedHashSet<>() : new LinkedHashSet<>(module.getPresentations()));
        definition.setParameters(deduplicateParameters((module.getParameters() == null ? List.<ModuleParameterDefinition>of() : module.getParameters()).stream()
                .map(parameter -> parameter(parameter.getKey(), parameter.getName(), parameter.getType(),
                        Boolean.TRUE.equals(parameter.getRequired()), parameter.getDescription(), parameter.getDefaultValue(), parameter.getVisible()))
                .toList()));
        definition.setGuides(guides == null ? List.of() : List.copyOf(guides));
        return definition;
    }

    /**
     * 判断任务定义是否允许指定能力命令。
     *
     * @param moduleCode 能力模块编码
     * @param commandCode 能力命令编码
     * @return 场景显式授权该命令时返回 true
     */
    public boolean allowsCommand(String moduleCode, String commandCode) {
        Set<String> commandCodes = capabilityCommands == null ? null : capabilityCommands.get(moduleCode);
        return commandCodes != null && commandCodes.contains(commandCode);
    }

    /**
     * 根据任务的最终参数值裁剪本次实际可用的能力和命令。
     *
     * @param inputValues 经过分析情境补充后的任务参数
     * @return 当前任务定义，能力集合已收敛为本次任务的有效快照
     */
    public AgentTaskDefinition activateCapabilities(List<TaskInputValue> inputValues) {
        Map<String, String> values = new LinkedHashMap<>();
        for (DefinitionParameter parameter : parameters == null ? List.<DefinitionParameter>of() : parameters) {
            if (parameter.key() != null && parameter.defaultValue() != null) {
                values.put(parameter.key(), parameter.defaultValue());
            }
        }
        for (TaskInputValue inputValue : inputValues == null ? List.<TaskInputValue>of() : inputValues) {
            if (inputValue != null && inputValue.getKey() != null) {
                values.put(inputValue.getKey().trim(), inputValue.getValue());
            }
        }

        LinkedHashSet<String> activeCapabilities = new LinkedHashSet<>();
        for (String capabilityCode : capabilities == null ? Set.<String>of() : capabilities) {
            CapabilityActivationCondition condition = capabilityConditions == null ? null : capabilityConditions.get(capabilityCode);
            if (condition == null || (condition.getValues() != null
                    && condition.getValues().contains(values.get(condition.getParameter())))) {
                activeCapabilities.add(capabilityCode);
            }
        }
        capabilities = activeCapabilities;
        capabilityCommands = copyCapabilityCommands(capabilityCommands);
        capabilityCommands.keySet().removeIf(code -> !activeCapabilities.contains(code));
        return this;
    }

    private static AgentTaskDefinition base(String code, String name, String description, String scenario, String promptText,
                                            Set<String> capabilities) {
        AgentTaskDefinition definition = new AgentTaskDefinition();
        definition.setCode(code);
        definition.setName(name);
        definition.setDescription(description);
        definition.setScenario(scenario);
        definition.setPromptText(promptText);
        definition.setCapabilities(capabilities == null ? new LinkedHashSet<>() : new LinkedHashSet<>(capabilities));
        return definition;
    }

    /**
     * 复制场景能力命令配置，避免任务定义与实体集合共享引用。
     *
     * @param capabilityCommands 场景实体保存的能力命令集合
     * @return 保持能力和命令顺序的独立集合
     */
    private static Map<String, Set<String>> copyCapabilityCommands(Map<String, Set<String>> capabilityCommands) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        if (capabilityCommands != null) {
            capabilityCommands.forEach((moduleCode, commandCodes) -> result.put(moduleCode,
                    commandCodes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(commandCodes)));
        }
        return result;
    }

    private static Map<String, CapabilityActivationCondition> copyCapabilityConditions(
            Map<String, CapabilityActivationCondition> capabilityConditions) {
        Map<String, CapabilityActivationCondition> result = new LinkedHashMap<>();
        if (capabilityConditions != null) {
            capabilityConditions.forEach((moduleCode, source) -> {
                if (source == null) {
                    return;
                }
                CapabilityActivationCondition condition = new CapabilityActivationCondition();
                condition.setParameter(source.getParameter());
                condition.setValues(source.getValues() == null ? new LinkedHashSet<>() : new LinkedHashSet<>(source.getValues()));
                result.put(moduleCode, condition);
            });
        }
        return result;
    }

    private static DefinitionParameter parameter(String key, String name, String type, boolean required, String description,
                                                 String defaultValue, Boolean visible) {
        return new DefinitionParameter(key, name, type, required, description, defaultValue, visible == null || visible);
    }

    private static List<DefinitionParameter> deduplicateParameters(List<DefinitionParameter> parameters) {
        Map<String, DefinitionParameter> result = new LinkedHashMap<>();
        for (DefinitionParameter parameter : parameters) {
            String key = parameter.key() == null ? "" : parameter.key().trim();
            if (!key.isEmpty()) {
                result.putIfAbsent(key, parameter);
            }
        }
        return List.copyOf(result.values());
    }

    private static DefinitionGuide guide(String key, String title, String description, String content) {
        return new DefinitionGuide(key, title, description, content);
    }

    public record DefinitionParameter(String key, String name, String type, boolean required, String description,
                                      String defaultValue, boolean visible) {
    }

    public record DefinitionGuide(String key, String title, String description, String content) {
    }
}
