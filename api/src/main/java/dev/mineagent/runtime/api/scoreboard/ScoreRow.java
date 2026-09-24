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
        if (!NativeScoreboardText.valid(holder)
                || !NativeScoreboardText.valid(displayName)
                || !NativeScoreboardText.valid(formattedScore)
                || iconSha256 == null || (!iconSha256.isEmpty() && !iconSha256.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("invalid score row");
        }
    }
}
