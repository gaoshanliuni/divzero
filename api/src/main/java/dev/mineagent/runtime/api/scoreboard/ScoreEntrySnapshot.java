package dev.mineagent.runtime.api.scoreboard;

import java.util.Objects;

public record ScoreEntrySnapshot(
        String objectiveName,
        String holder,
        int score,
        String displayName,
        NumberFormatSpec numberFormat
) {
    public ScoreEntrySnapshot {
        if (!NativeScoreboardText.valid(objectiveName)
                || !NativeScoreboardText.valid(holder)
                || !NativeScoreboardText.valid(displayName)) {
            throw new IllegalArgumentException("invalid scoreboard entry snapshot");
        }
        Objects.requireNonNull(numberFormat, "numberFormat");
    }
}
