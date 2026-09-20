package dev.mineagent.runtime.core.scoreboard;

import java.util.Set;
import java.util.UUID;

public record ScoreAudienceContext(
        UUID playerId,
        Set<String> teams,
        Set<String> permissionGroups,
        Set<String> scenes
) {
    public ScoreAudienceContext {
        teams = Set.copyOf(teams);
        permissionGroups = Set.copyOf(permissionGroups);
        scenes = Set.copyOf(scenes);
    }
}
