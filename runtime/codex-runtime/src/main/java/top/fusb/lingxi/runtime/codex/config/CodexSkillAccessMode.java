package top.fusb.lingxi.runtime.codex.config;

public enum CodexSkillAccessMode {
    // 将标准 Skill 投影成公开命令目录，不注册到 Codex 原生 Skill 系统。
    COMMAND_CATALOG,
    // 将标准 Skill 注册到 Codex 原生 Skill 系统。
    NATIVE_SKILL
}
