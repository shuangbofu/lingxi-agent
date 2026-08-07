package top.fusb.lingxi.prompt;

import top.fusb.lingxi.definition.ModuleDefinition;
import top.fusb.lingxi.dto.AgentTaskDefinition;
import top.fusb.lingxi.dto.TaskCreateRequest;
import top.fusb.lingxi.dto.TaskInputValue;
import top.fusb.lingxi.runtime.config.RuntimeConfigService;
import top.fusb.lingxi.service.ModuleDefinitionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromptServiceTest {

    @Test
    void keepsScenarioContractAndUsesLazySkillLoading() {
        ModuleDefinitionService moduleDefinitionService = mock(ModuleDefinitionService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        PromptService service = new PromptService(
                moduleDefinitionService,
                runtimeConfigService,
                new PromptTemplateService()
        );
        ModuleDefinition capability = new ModuleDefinition();
        capability.setCode("sample-capability");
        capability.setName("样例能力");
        capability.setDescription("用于验证按需加载能力说明");
        when(moduleDefinitionService.listCapabilities()).thenReturn(List.of(capability));
        when(runtimeConfigService.globalBoundaryPrompt()).thenReturn("平台边界标记");

        AgentTaskDefinition definition = new AgentTaskDefinition();
        definition.setCode("sample-scenario");
        definition.setScenario("analysis");
        definition.setPromptText("场景交付要求标记");
        definition.setCapabilities(Set.of("sample-capability"));
        definition.setPresentations(Set.of("timeline", "evidence"));
        definition.setParameters(List.of(new AgentTaskDefinition.DefinitionParameter(
                "userInput", "业务问题", "textarea", true, "需要分析的问题", null, true)));
        TaskCreateRequest request = new TaskCreateRequest();
        request.setUserInput("动态用户输入标记");
        TaskInputValue environment = new TaskInputValue();
        environment.setKey("environment");
        environment.setValue("PROD");
        request.setInputValues(List.of(environment));

        TaskPrompt prompt = service.buildTaskPrompt("analysis", definition, request, "动态情境标记");

        assertThat(prompt.instructions())
                .contains("场景交付要求标记", "样例能力", "# 已挂载 Agent Skills",
                        "括号内的 code 是稳定的 Skill name",
                        "`.agent-task/` 保存任务恢复和 Skill 引用所需的持久上下文",
                        "`.agent-task/task.md`", "`.agent-task/context.md`", "`.agent-task/scenario.md`",
                        "`.agent-task/capabilities.md`", "`.agent-task/attachments.md`",
                        "`.agent-task/resources.json`", "`.agent-task/source-task.md`",
                        "`.agent-task/artifacts/`", "标记为“完整用户输入，分析前必须读取此文件”")
                .contains("平台边界标记")
                .contains("- `userInput` (业务问题)：textarea，必填；需要分析的问题")
                .contains("资源记忆通过当前 Runtime 提供的专用结构化工具使用",
                        "`.agent-task/capabilities.md` 中的副本只用于恢复流程")
                .doesNotContain("\"key\":\"userInput\"", "\"required\":true", "# 可用能力命令",
                        "read_skill_file", "平台命令说明", "能力完整正文标记",
                        "动态用户输入标记", "动态情境标记", "lingxi-view");
        assertThat(prompt.userMessage())
                .contains("动态用户输入标记", "动态情境标记", "- environment: PROD")
                .doesNotContain("\"key\":\"environment\"", "\"value\":\"PROD\"")
                .doesNotContain("场景交付要求标记", "平台边界标记", "lingxi-view");
        assertThat(prompt.finalResponseInstructions())
                .contains("# 最终答案展示增强", "## 时间线", "## 证据链", "lingxi-view")
                .doesNotContain("## Mermaid 图", "## 关键指标", "## 步骤状态");
        assertThat(prompt.combined()).doesNotContain("lingxi-view");
        assertThat(prompt.combined().indexOf("场景交付要求标记"))
                .isLessThan(prompt.combined().indexOf("动态用户输入标记"));
        verify(moduleDefinitionService, never()).readPrompt(capability);
    }

    @Test
    void skipsFinalPresentationInstructionsWhenScenarioDoesNotDeclareThem() {
        PromptTemplateService templateService = new PromptTemplateService();

        assertThat(templateService.presentationInstructions(Set.of())).isEmpty();
    }
}
