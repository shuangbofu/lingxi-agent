package top.fusb.lingxi.runtime.codex.cli;

import lombok.Getter;

@Getter
public enum CodexSandboxMode {

    READ_ONLY("read-only"),
    WORKSPACE_WRITE("workspace-write"),
    DANGER_FULL_ACCESS("danger-full-access");

    private final String cliValue;

    CodexSandboxMode(String cliValue) {
        this.cliValue = cliValue;
    }
}
