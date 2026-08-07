package top.fusb.lingxi.prompt;

import top.fusb.lingxi.dto.AgentTaskDefinition;
import top.fusb.lingxi.dto.TaskCreateRequest;
import top.fusb.lingxi.dto.TaskInputValue;
import top.fusb.lingxi.definition.ModuleDefinition;
import top.fusb.lingxi.runtime.config.RuntimeConfigService;
import top.fusb.lingxi.service.ModuleDefinitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptService {

    private final ModuleDefinitionService moduleDefinitionService;
    private final RuntimeConfigService runtimeConfigService;
    private final PromptTemplateService promptTemplateService;

    /**
     * 构建具有明确消息角色的任务 Prompt，稳定平台契约与不可信任务输入不会再混入同一条消息。
     *
     * @param scenario 场景标识
     * @param definition 场景任务定义
     * @param request 用户提交的任务请求
     * @param premiseText 当前分析情境及其上下文
     * @return 可分别交给 Runtime 系统消息和用户消息的任务 Prompt
     */
    public TaskPrompt buildTaskPrompt(String scenario, AgentTaskDefinition definition, TaskCreateRequest request,
                                      String premiseText) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("scenario", nullToEmpty(scenario));
        variables.put("parameterDefinitions", parameterDefinitions(definition));
        variables.put("workspaceContext", promptTemplateService.render("workspace-context", Map.of()));
        variables.put("followUpCandidates", followUpScenarioCandidates(definition));
        variables.put("globalPrinciples", globalDefinitionPrinciples());
        variables.put("capabilityModules", availableCapabilityModules(definition));
        variables.put("definitionPrompt", definition == null ? "" : nullToEmpty(definition.getPromptText()));
        variables.put("guides", definitionGuides(definition));
        String instructions = promptTemplateService.render("task-instructions", variables);

        Map<String, String> inputVariables = new LinkedHashMap<>();
        inputVariables.put("userInput", escapeUntrustedData(nullToEmpty(request.getUserInput()).trim()));
        inputVariables.put("inputValues", escapeUntrustedData(inputValues(request.getInputValues())));
        inputVariables.put("premiseText", nullToEmpty(premiseText));
        inputVariables.put("sourceTaskContext", buildSourceTaskContext(request.getSourceTaskId()));
        return new TaskPrompt(instructions,
                promptTemplateService.render("task-user-message", inputVariables),
                promptTemplateService.presentationInstructions(definition == null ? Set.of() : definition.getPresentations()));
    }

    private String globalDefinitionPrinciples() {
        return promptTemplateService.render("global-principles",
                Map.of("globalBoundaryPrompt", nullToEmpty(runtimeConfigService.globalBoundaryPrompt())));
    }

    private String followUpScenarioCandidates(AgentTaskDefinition definition) {
        if (definition == null) {
            return promptTemplateService.text("followUp.empty");
        }
        Set<String> recommendationCodes = moduleDefinitionService.scenarioRecommendationCodes(definition.getCode());
        if (recommendationCodes.isEmpty()) {
            return promptTemplateService.text("followUp.empty");
        }
        StringBuilder builder = new StringBuilder();
        List<ModuleDefinition> scenarioModules = moduleDefinitionService.listInstalledScenarios();
        for (String candidateCode : recommendationCodes) {
            ModuleDefinition module = scenarioModules.stream()
                    .filter(item -> candidateCode.equals(item.getCode()))
                    .findFirst()
                    .orElse(null);
            builder.append("- ").append(candidateCode);
            if (module != null) {
                builder.append(": ").append(module.getName() == null ? candidateCode : module.getName());
                if (module.getDescription() != null && !module.getDescription().isBlank()) {
                    builder.append(" - ").append(module.getDescription().trim());
                }
            }
            builder.append(System.lineSeparator());
        }
        return promptTemplateService.render("follow-up-recommendation",
                Map.of("candidates", builder.toString().trim()));
    }

    private String availableCapabilityModules(AgentTaskDefinition definition) {
        if (definition == null || definition.getCapabilities().isEmpty()) {
            return promptTemplateService.text("capabilities.empty");
        }
        StringBuilder builder = new StringBuilder();
        for (ModuleDefinition module : moduleDefinitionService.listCapabilities()) {
            if (module.getCode() == null || !definition.getCapabilities().contains(module.getCode())) {
                continue;
            }
            builder.append("- ").append(module.getName()).append(" (`").append(module.getCode()).append("`)");
            if (module.getDescription() != null && !module.getDescription().isBlank()) {
                builder.append(": ").append(module.getDescription().trim());
            }
            builder.append(System.lineSeparator());
        }
        return builder.isEmpty() ? promptTemplateService.text("capabilities.missing") : builder.toString();
    }


    private String parameterDefinitions(AgentTaskDefinition definition) {
        if (definition == null || definition.getParameters() == null || definition.getParameters().isEmpty()) {
            return promptTemplateService.text("parameters.empty");
        }
        StringBuilder builder = new StringBuilder();
        for (AgentTaskDefinition.DefinitionParameter parameter : definition.getParameters()) {
            builder.append("- `").append(oneLine(parameter.key())).append("` (")
                    .append(oneLine(parameter.name())).append(")：")
                    .append(oneLine(parameter.type())).append(parameter.required() ? "，必填" : "，可选");
            if (parameter.description() != null && !parameter.description().isBlank()) {
                builder.append("；").append(oneLine(parameter.description()));
            }
            builder.append(System.lineSeparator());
        }
        return builder.toString().strip();
    }

    private String definitionGuides(AgentTaskDefinition definition) {
        if (definition == null || definition.getGuides() == null || definition.getGuides().isEmpty()) {
            return promptTemplateService.text("guides.empty");
        }
        StringBuilder builder = new StringBuilder();
        for (AgentTaskDefinition.DefinitionGuide guide : definition.getGuides()) {
            if (!builder.isEmpty()) {
                builder.append(System.lineSeparator()).append(System.lineSeparator());
            }
            builder.append("## ").append(guide.title()).append(" (").append(guide.key()).append(")")
                    .append(System.lineSeparator());
            if (guide.description() != null && !guide.description().isBlank()) {
                builder.append(guide.description().trim()).append(System.lineSeparator()).append(System.lineSeparator());
            }
            builder.append(guide.content().trim());
        }
        return builder.toString();
    }

    private String inputValues(List<TaskInputValue> values) {
        if (values == null || values.isEmpty()) {
            return "（无）";
        }
        StringBuilder builder = new StringBuilder();
        for (TaskInputValue input : values) {
            if (input == null || input.getKey() == null || input.getKey().isBlank()) {
                continue;
            }
            String value = input.getValue() == null ? "" : input.getValue().replace("\r\n", "\n").replace('\r', '\n');
            builder.append("- ").append(input.getKey().trim()).append(":");
            if (value.contains("\n")) {
                builder.append(" |\n  ").append(value.replace("\n", "\n  "));
            } else {
                builder.append(' ').append(value);
            }
            builder.append(System.lineSeparator());
        }
        return builder.isEmpty() ? "（无）" : builder.toString().strip();
    }

    private String oneLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String escapeUntrustedData(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }

    private String buildSourceTaskContext(Long sourceTaskId) {
        if (sourceTaskId == null) {
            return promptTemplateService.text("sourceTask.empty");
        }
        return promptTemplateService.render("source-task-context",
                Map.of("sourceTaskId", sourceTaskId.toString()));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
