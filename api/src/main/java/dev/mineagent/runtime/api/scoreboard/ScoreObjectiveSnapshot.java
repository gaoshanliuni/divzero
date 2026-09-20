package dev.mineagent.runtime.api.scoreboard;

import java.util.Objects;

public record ScoreObjectiveSnapshot(
        String name,
        String criteria,
        boolean readOnly,
        String displayName,
        String renderType,
        boolean autoUpdate,
        NumberFormatSpec numberFormat
) {
    public ScoreObjectiveSnapshot {
        if (name == null || name.isBlank() || name.length() > 16 || name.chars().anyMatch(Character::isISOControl)
                || criteria == null || criteria.isBlank() || criteria.length() > 128
                || displayName == null || displayName.length() > 2_048
                || renderType == null || !renderType.matches("[A-Z_]{1,32}")) {
            throw new IllegalArgumentException("invalid scoreboard objective snapshot");
        }
        Objects.requireNonNull(numberFormat, "numberFormat");
    }
}
