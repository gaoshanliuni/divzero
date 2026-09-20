package dev.mineagent.runtime.core.scoreboard;

import dev.mineagent.runtime.api.scoreboard.ScoreboardSnapshot;

import java.util.Set;

public record ScoreboardDelta(
        long revision,
        boolean full,
        Set<String> changedObjectives,
        ScoreboardSnapshot snapshot
) {
    public ScoreboardDelta {
        changedObjectives = Set.copyOf(changedObjectives);
    }
}
