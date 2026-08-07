package top.fusb.lingxi.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PromptTemplateServiceTest {

    private final PromptTemplateService service = new PromptTemplateService();

    @Test
    void rendersNamedVariablesAndKeepsPlaceholdersFromDynamicContent() {
        String rendered = service.render("source-task-context", Map.of("sourceTaskId", "${HOME}"));

        assertThat(rendered)
                .contains("来源任务ID：${HOME}")
                .contains(".agent-task/source-task.md");
    }

    @Test
    void loadsPromptDefaultsFromResource() {
        assertThat(service.text("parameters.empty")).isEqualTo("[]");
        assertThat(service.text("sourceTask.empty")).isEqualTo("无来源任务。");
    }
}
