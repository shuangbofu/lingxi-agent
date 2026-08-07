package top.fusb.lingxi.runtime.langchain.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.runtime.api.execution.RuntimeCommandParameterDescriptor;
import top.fusb.lingxi.runtime.api.execution.RuntimeCommandParameterType;
import top.fusb.lingxi.runtime.langchain.agent.LangChainExecutionContext;
import top.fusb.lingxi.runtime.langchain.agent.tool.LangChainToolExecutors;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LangChainRegistryToolProvider implements ToolProvider {

    private static final String SKILL_COMMAND_TOOL = "run_skill_command";

    private final LangChainCapabilityRegistry registry;
    private final LangChainWorkspaceTools workspaceTools;
    private final ObjectMapper objectMapper;
    private final LangChainExecutionContext context;

    public LangChainRegistryToolProvider(LangChainCapabilityRegistry registry,
                                         LangChainWorkspaceTools workspaceTools,
                                         ObjectMapper objectMapper) {
        this.registry = registry;
        this.workspaceTools = workspaceTools;
        this.objectMapper = objectMapper;
        this.context = workspaceTools == null ? null : workspaceTools.executionContext();
    }

    /**
     * 将当前执行授权的平台注册命令逐条转换为原生工具，不改变命令的平台或外部能力归属。
     *
     * @param request LangChain4j 当前工具提供请求
     * @return 原生工具规格及其对应执行器
     */
    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
        for (LangChainCapabilityRegistry.NativeCommand command : registry.nativeCommands()) {
            ToolSpecification specification = ToolSpecification.builder()
                    .name(command.toolName())
                    .description(toolDescription(command))
                    .parameters(toolParameters(command))
                    .build();
            ToolExecutor executor = (toolRequest, memoryId) -> execute(command, toolRequest);
            tools.put(specification, LangChainToolExecutors.guarded(
                    executor, command.descriptor().executionMode(), context));
        }

        List<LangChainCapabilityRegistry.SkillCommand> skillCommands = registry.skillCommands().stream()
                .filter(command -> registry.skillInstructionsRead(command.skillName()))
                .toList();
        if (!skillCommands.isEmpty()) {
            ToolSpecification specification = ToolSpecification.builder()
                    .name(SKILL_COMMAND_TOOL)
                    .description("执行已完整读取 SKILL.md 的 Agent Skill 业务命令。参数必须严格来自已读取的命令用法，不得猜测或传脚本路径。")
                    .parameters(skillCommandParameters(skillCommands))
                    .build();
            tools.put(specification, (toolRequest, memoryId) -> executeSkillCommand(toolRequest));
        }
        return ToolProviderResult.builder().addAll(tools).build();
    }

    @Override
    public boolean isDynamic() {
        return true;
    }

    private String executeSkillCommand(ToolExecutionRequest request) {
        try {
            JsonNode root = objectMapper.readTree(request.arguments());
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("run_skill_command 参数必须是 JSON 对象");
            }
            JsonNode command = root.get("command");
            if (command == null || !command.isTextual() || command.textValue().isBlank()) {
                throw new IllegalArgumentException("command 必须是当前任务授权的 Skill 业务命令");
            }
            LangChainCapabilityRegistry.SkillCommand skillCommand =
                    registry.resolveSkillCommand(command.textValue());
            registry.requireSkillInstructionsRead(skillCommand);
            ToolExecutor executor = (toolRequest, memoryId) -> {
                try {
                    return workspaceTools.runSkillCommand(
                            command.textValue(), stringArguments(root.get("arguments"), true));
                } catch (RuntimeException exception) {
                    throw exception;
                } catch (Exception exception) {
                    throw new IllegalStateException(exception.getMessage(), exception);
                }
            };
            return LangChainToolExecutors.guarded(
                    executor, skillCommand.descriptor().executionMode(), context).execute(request, null);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    private String execute(LangChainCapabilityRegistry.NativeCommand command,
                           ToolExecutionRequest request) {
        try {
            JsonNode root = objectMapper.readTree(request.arguments());
            if (!command.descriptor().parameters().isEmpty()) {
                return workspaceTools.runCapabilityCommand(command.commandKey(), typedArguments(command, root));
            }
            JsonNode values = root.path("arguments");
            if (values.isMissingNode() || values.isNull()) {
                return workspaceTools.runCapabilityCommand(command.commandKey(), List.of());
            }
            return workspaceTools.runCapabilityCommand(command.commandKey(), stringArguments(values, false));
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    private String toolDescription(LangChainCapabilityRegistry.NativeCommand command) {
        StringBuilder description = new StringBuilder(command.name());
        if (command.description() != null && !command.description().isBlank()) {
            description.append("：").append(command.description());
        }
        if (command.descriptor().parameters().isEmpty()
                && command.usage() != null && !command.usage().isBlank()) {
            description.append("\n参数用法：").append(command.usage());
        }
        return description.toString();
    }

    private JsonObjectSchema skillCommandParameters(
            List<LangChainCapabilityRegistry.SkillCommand> skillCommands) {
        return JsonObjectSchema.builder()
                .addProperty("command", JsonEnumSchema.builder()
                        .description("刚刚完整读取的 SKILL.md 命令章节中声明的完整业务命令")
                        .enumValues(skillCommands.stream()
                                .map(LangChainCapabilityRegistry.SkillCommand::command)
                                .toList())
                        .build())
                .addProperty("arguments", JsonArraySchema.builder()
                        .description("严格按 SKILL.md 用法填写的命令参数，不包含 group 和 action；只有说明明确无参数时才传空数组")
                        .items(new JsonStringSchema())
                        .build())
                .required("command", "arguments")
                .additionalProperties(false)
                .build();
    }

    private List<String> stringArguments(JsonNode values, boolean required) {
        if (values == null || values.isMissingNode() || values.isNull()) {
            if (required) {
                throw new IllegalArgumentException("arguments 必须是字符串数组");
            }
            return List.of();
        }
        if (!values.isArray()) {
            throw new IllegalArgumentException("arguments 必须是字符串数组");
        }
        List<String> arguments = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                throw new IllegalArgumentException("arguments 只能包含字符串");
            }
            arguments.add(value.asText());
        }
        return List.copyOf(arguments);
    }

    /**
     * 将平台注册命令参数转换成模型原生 JSON Schema，未声明参数时保留通用数组协议。
     *
     * @param command 当前任务授权的平台命令
     * @return 可交给 LangChain4j 注册的工具参数 Schema
     */
    private JsonObjectSchema toolParameters(LangChainCapabilityRegistry.NativeCommand command) {
        if (command.descriptor().parameters().isEmpty()) {
            return JsonObjectSchema.builder()
                    .addProperty("arguments", JsonArraySchema.builder()
                            .description("命令参数数组，只填写该工具用法中声明的选项和值；无参数时传空数组")
                            .items(new JsonStringSchema())
                            .build())
                    .additionalProperties(false)
                    .build();
        }
        JsonObjectSchema.Builder schema = JsonObjectSchema.builder().additionalProperties(false);
        List<String> required = new ArrayList<>();
        for (RuntimeCommandParameterDescriptor parameter : command.descriptor().parameters()) {
            if (parameter.type() == RuntimeCommandParameterType.STRING) {
                schema.addProperty(parameter.name(), JsonStringSchema.builder()
                        .description(parameter.description()).build());
            } else if (parameter.type() == RuntimeCommandParameterType.BOOLEAN) {
                schema.addProperty(parameter.name(), JsonBooleanSchema.builder()
                        .description(parameter.description()).build());
            } else {
                throw new IllegalStateException("不支持的平台命令参数类型：" + parameter.type());
            }
            if (parameter.required()) {
                required.add(parameter.name());
            }
        }
        if (!required.isEmpty()) {
            schema.required(required);
        }
        return schema.build();
    }

    /**
     * 按平台注册参数顺序把结构化工具参数转换为私有可执行入口需要的 CLI 参数。
     *
     * @param command 当前任务授权的平台命令
     * @param root 模型提交的结构化工具参数
     * @return 不包含可执行文件和命令前缀的 CLI 参数
     * @throws IllegalArgumentException 必填参数缺失或参数类型错误时抛出
     */
    private List<String> typedArguments(LangChainCapabilityRegistry.NativeCommand command, JsonNode root) {
        List<String> arguments = new ArrayList<>();
        for (RuntimeCommandParameterDescriptor parameter : command.descriptor().parameters()) {
            JsonNode value = root.get(parameter.name());
            if (value == null || value.isNull()) {
                if (parameter.required()) {
                    throw new IllegalArgumentException("缺少必要参数：" + parameter.name());
                }
                continue;
            }
            if (parameter.type() == RuntimeCommandParameterType.STRING) {
                if (!value.isTextual() || value.asText().isBlank()) {
                    throw new IllegalArgumentException(parameter.name() + " 必须是非空字符串");
                }
                arguments.add(parameter.option());
                arguments.add(value.asText());
            } else if (parameter.type() == RuntimeCommandParameterType.BOOLEAN) {
                if (!value.isBoolean()) {
                    throw new IllegalArgumentException(parameter.name() + " 必须是布尔值");
                }
                if (value.asBoolean()) {
                    arguments.add(parameter.option());
                }
            } else {
                throw new IllegalStateException("不支持的平台命令参数类型：" + parameter.type());
            }
        }
        return arguments;
    }
}
