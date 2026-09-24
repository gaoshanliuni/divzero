package dev.mineagent.runtime.api.scoreboard;

import java.util.Set;

public record ScoreTeamSnapshot(
        String name,
        String displayName,
        String color,
        Set<String> members
) {
    public ScoreTeamSnapshot {
        if (!NativeScoreboardText.valid(name)
                || !NativeScoreboardText.valid(displayName)
                || color == null || color.length() > 32) {
            throw new IllegalArgumentException("invalid score team snapshot");
        }
        members = Set.copyOf(members);
    }
}
