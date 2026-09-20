package dev.mineagent.runtime.client.control;

import java.util.Map;
import java.util.Objects;

public final class AppearancePanelStatus {
    private AppearancePanelStatus() {
    }

    public static String format(
            Map<String, String> panelValues,
            String agentPrefix,
            boolean commandAccepted,
            String commandErrorCode,
            long commandRevision
    ) {
        Objects.requireNonNull(panelValues, "panelValues");
        Objects.requireNonNull(agentPrefix, "agentPrefix");
        String persistedStatus = panelValues.getOrDefault(agentPrefix + "appearanceStatus", "");
        if (!persistedStatus.isBlank() && !"READY".equals(persistedStatus)) {
            String diagnostic = panelValues.getOrDefault(agentPrefix + "appearanceDiagnostic", "");
            String revision = panelValues.getOrDefault(
                    agentPrefix + "appearanceRevision", Long.toString(commandRevision));
            return "状态: " + persistedStatus
                    + (diagnostic.isBlank() ? "" : " / " + diagnostic)
                    + " r" + revision
                    + defaultSuggestion(persistedStatus, diagnostic);
        }
        String visibleState = commandAccepted ? "已应用" : commandErrorCode;
        return "状态: " + visibleState + " r" + commandRevision
                + defaultSuggestion(visibleState, commandErrorCode);
    }

    public static String formatForAgent(
            Map<String, String> panelValues,
            String agentPrefix,
            String currentAgentId,
            String commandAgentId,
            boolean commandAccepted,
            String commandErrorCode,
            long commandRevision
    ) {
        Objects.requireNonNull(currentAgentId, "currentAgentId");
        if (currentAgentId.equals(commandAgentId)) {
            return format(panelValues, agentPrefix, commandAccepted, commandErrorCode, commandRevision);
        }
        Objects.requireNonNull(panelValues, "panelValues");
        Objects.requireNonNull(agentPrefix, "agentPrefix");
        String status = panelValues.getOrDefault(agentPrefix + "appearanceStatus", "NOT_RUN");
        String diagnostic = panelValues.getOrDefault(agentPrefix + "appearanceDiagnostic", "");
        String revision = panelValues.getOrDefault(agentPrefix + "appearanceRevision", "0");
        return "状态: " + status
                + (diagnostic.isBlank() ? "" : " / " + diagnostic)
                + " r" + revision
                + defaultSuggestion(status, diagnostic);
    }

    public static Draft requestedDraft(Map<String, String> panelValues, String agentPrefix) {
        Objects.requireNonNull(panelValues, "panelValues");
        Objects.requireNonNull(agentPrefix, "agentPrefix");
        return new Draft(
                panelValues.getOrDefault(agentPrefix + "requestedModel",
                        panelValues.getOrDefault(agentPrefix + "model", "")),
                panelValues.getOrDefault(agentPrefix + "requestedTexture",
                        panelValues.getOrDefault(agentPrefix + "texture", "")),
                panelValues.getOrDefault(agentPrefix + "requestedAnimation",
                        panelValues.getOrDefault(agentPrefix + "animation", ""))
        );
    }

    private static String defaultSuggestion(String status, String diagnostic) {
        return "ASSET_UNAVAILABLE".equals(status) || "YSM_ASSET_UNAVAILABLE".equals(diagnostic)
                ? " · 可用建议: default"
                : "";
    }

    public record Draft(String modelId, String textureId, String animationId) {
    }
}
