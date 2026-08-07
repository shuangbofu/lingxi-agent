package top.fusb.lingxi.runtime.codex.config;

import top.fusb.lingxi.runtime.codex.cli.CodexSandboxMode;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "lingxi.codex")
public class CodexRuntimeProperties {

    private String executable = "codex";
    private String installDir = "./data/codex-cli";
    private long defaultTimeoutSeconds = 900;
    private String locale = "zh_CN.UTF-8";
    private CodexSandboxMode sandboxMode = CodexSandboxMode.DANGER_FULL_ACCESS;
    private boolean networkAccess = true;
    private CodexSkillAccessMode skillAccessMode = CodexSkillAccessMode.COMMAND_CATALOG;
}
