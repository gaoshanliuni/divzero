package dev.mineagent.runtime.api.config;

public enum PanelSection {
    OVERVIEW("总览", false),
    AGENTS("AI 玩家", false),
    CONVERSATIONS("会话与选择", false),
    TASKS("任务", false),
    CREATOR("创造器", false),
    CODE_STUDIO("Code Studio", false),
    PACKAGES("内容包", false),
    APPEARANCE("外观 / YSM", false),
    MEDIA("媒体", false),
    SCOREBOARDS("计分板", false),
    MOD_KNOWLEDGE("Mod 知识", false),
    MEMORY("记忆", false),
    PROVIDERS("Provider", true),
    PERMISSIONS("权限与信任", false),
    BACKUPS("备份恢复", true),
    DIAGNOSTICS("诊断", true);

    private final String displayName;
    private final boolean operatorOnly;

    PanelSection(String displayName, boolean operatorOnly) {
        this.displayName = displayName;
        this.operatorOnly = operatorOnly;
    }

    public String displayName() {
        return displayName;
    }

    public boolean operatorOnly() {
        return operatorOnly;
    }
}
