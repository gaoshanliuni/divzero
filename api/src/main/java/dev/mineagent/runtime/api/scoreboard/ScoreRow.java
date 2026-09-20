package dev.mineagent.runtime.api.scoreboard;

import java.util.Objects;

public record ScoreRow(
        String holder,
        String displayName,
        int score,
        String formattedScore,
        String iconSha256
) {
    public ScoreRow {
        if (holder == null || holder.isBlank() || holder.length() > 40
                || displayName == null || displayName.length() > 2_048
                || formattedScore == null || formattedScore.length() > 2_048
                || iconSha256 == null || (!iconSha256.isEmpty() && !iconSha256.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("invalid score row");
        }
    }
}
