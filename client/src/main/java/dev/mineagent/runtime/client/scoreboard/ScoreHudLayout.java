package dev.mineagent.runtime.client.scoreboard;

import dev.mineagent.runtime.api.scoreboard.ScoreRow;

import java.util.List;

public record ScoreHudLayout(
        int x,
        int y,
        int width,
        float scale,
        String title,
        List<ScoreRow> rows
) {
    public ScoreHudLayout {
        rows = List.copyOf(rows);
    }
}
