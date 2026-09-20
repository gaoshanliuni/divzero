package dev.mineagent.runtime.core.config;

import dev.mineagent.runtime.api.config.PanelSnapshot;

/** Immutable per-send input/summary policy. Estimates are not Provider usage or billing totals. */
public record ConversationBudget(long configRevision, int contextTokenBudget, int summaryMaxCalls,
                                 int summaryInputBudget) {
    public static final String ESTIMATE_MODE = "UTF8_BYTE_UPPER_BOUND";

    public ConversationBudget {
        if (configRevision < 0 || contextTokenBudget < 1024 || contextTokenBudget > 131072
                || summaryMaxCalls < 0 || summaryMaxCalls > 64
                || summaryInputBudget < 2048 || summaryInputBudget > 131072) {
            throw new IllegalArgumentException("CONVERSATION_BUDGET_INVALID");
        }
    }

    public static ConversationBudget from(PanelSnapshot snapshot) {
        try {
            var values = snapshot.values();
            return new ConversationBudget(snapshot.revision(),
                    Integer.parseInt(values.getOrDefault("conversation.contextTokenBudget", "32768")),
                    Integer.parseInt(values.getOrDefault("conversation.summary.maxCalls", "8")),
                    Integer.parseInt(values.getOrDefault("conversation.summary.inputBudget", "16384")));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("CONVERSATION_BUDGET_INVALID", failure);
        }
    }
}
