package top.fusb.lingxi.runtime.langchain.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangChainPromptKitTest {

    @Test
    void loadsAndFormatsPromptResourcesFromTheUnifiedDirectory() {
        assertThat(LangChainPromptKit.load("langchain-final-delivery-system.md"))
                .startsWith("# 最终交付阶段")
                .contains("调查工具已经关闭");
        assertThat(LangChainPromptKit.format("langchain-final-delivery-user.md", "原始目标", "调查草稿"))
                .contains("<original-task>\n原始目标\n</original-task>")
                .contains("<investigation-draft>\n调查草稿\n</investigation-draft>");
    }

    @Test
    void reportsMissingResourcesAndInvalidTemplateArguments() {
        assertThatThrownBy(() -> LangChainPromptKit.load("missing.md"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/prompts/missing.md");
        assertThatThrownBy(() -> LangChainPromptKit.format("langchain-final-delivery-user.md", "只有一个参数"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("langchain-final-delivery-user.md");
    }
}
