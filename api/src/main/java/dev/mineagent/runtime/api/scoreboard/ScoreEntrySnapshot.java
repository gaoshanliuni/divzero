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
        if (objectiveName == null || objectiveName.isBlank() || objectiveName.length() > 16
                || objectiveName.chars().anyMatch(Character::isISOControl)
                || holder == null || holder.isBlank() || holder.length() > 40
                || displayName == null || displayName.length() > 2_048) {
            throw new IllegalArgumentException("invalid scoreboard entry snapshot");
        }
        Objects.requireNonNull(numberFormat, "numberFormat");
    }
}
