package top.fusb.lingxi.runtime.codex.maintenance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

enum CodexRuntimeOs {

    LINUX,
    MAC,
    WINDOWS,
    UNKNOWN;

    static CodexRuntimeOs current() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return WINDOWS;
        }
        if (osName.contains("mac") || osName.contains("darwin")) {
            return MAC;
        }
        if (osName.contains("nux") || osName.contains("nix") || osName.contains("aix")) {
            return LINUX;
        }
        return UNKNOWN;
    }

    List<List<String>> buildOnlineInstallCommands() {
        List<List<String>> commands = new ArrayList<>();
        if (this == WINDOWS) {
            commands.add(List.of("cmd", "/c", "npm install -g @openai/codex"));
            commands.add(List.of("powershell", "-ExecutionPolicy", "ByPass", "-Command",
                    "irm https://chatgpt.com/codex/install.ps1 | iex"));
        } else if (this == MAC || this == LINUX) {
            commands.add(List.of("sh", "-lc",
                    "curl -fsSL --connect-timeout 10 --max-time 120 https://chatgpt.com/codex/install.sh | CODEX_NON_INTERACTIVE=1 sh"));
        }
        return commands;
    }
}
