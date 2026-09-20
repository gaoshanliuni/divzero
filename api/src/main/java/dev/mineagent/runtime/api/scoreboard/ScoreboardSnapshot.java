package dev.mineagent.runtime.api.scoreboard;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ScoreboardSnapshot(
        List<ScoreObjectiveSnapshot> objectives,
        List<ScoreEntrySnapshot> entries,
        Map<String, String> displaySlots,
        List<ScoreTeamSnapshot> teams
) {
    public ScoreboardSnapshot(
            List<ScoreObjectiveSnapshot> objectives,
            List<ScoreEntrySnapshot> entries,
            Map<String, String> displaySlots
    ) {
        this(objectives, entries, displaySlots, List.of());
    }

    public ScoreboardSnapshot {
        objectives = List.copyOf(Objects.requireNonNull(objectives, "objectives"));
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        displaySlots = Map.copyOf(Objects.requireNonNull(displaySlots, "displaySlots"));
        teams = List.copyOf(Objects.requireNonNull(teams, "teams"));
        if (objectives.size() > 16_384 || entries.size() > 1_000_000
                || displaySlots.size() > 64 || teams.size() > 1_024) {
            throw new IllegalArgumentException("scoreboard snapshot exceeds bounds");
        }
    }
}
