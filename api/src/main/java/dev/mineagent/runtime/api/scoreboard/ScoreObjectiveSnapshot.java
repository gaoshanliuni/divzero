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
        if (!NativeScoreboardText.valid(name)
                || !NativeScoreboardText.valid(criteria)
                || !NativeScoreboardText.valid(displayName)
                || renderType == null || !renderType.matches("[A-Z_]{1,32}")) {
            throw new IllegalArgumentException("invalid scoreboard objective snapshot");
        }
        Objects.requireNonNull(numberFormat, "numberFormat");
    }
}
