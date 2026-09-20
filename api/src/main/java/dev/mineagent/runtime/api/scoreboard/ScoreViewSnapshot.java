package dev.mineagent.runtime.api.scoreboard;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ScoreViewSnapshot(
        UUID viewId,
        long revision,
        boolean full,
        String title,
        List<ScoreRow> rows,
        Map<String, String> layout
) {
    public ScoreViewSnapshot {
        Objects.requireNonNull(viewId, "viewId");
        if (revision < 1 || title == null || title.length() > 2_048) {
            throw new IllegalArgumentException("invalid score view snapshot");
        }
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        layout = Map.copyOf(Objects.requireNonNull(layout, "layout"));
        if (rows.size() > 1_024 || layout.size() > 128) {
            throw new IllegalArgumentException("score view snapshot exceeds bounds");
        }
    }
}
