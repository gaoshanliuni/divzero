package dev.mineagent.runtime.core.scoreboard;

import java.util.Objects;
import java.util.Set;

public record ScoreAudience(ScoreAudienceKind kind, Set<String> members) {
    public ScoreAudience {
        Objects.requireNonNull(kind, "kind");
        members = Set.copyOf(Objects.requireNonNull(members, "members"));
        if (members.size() > 1024 || members.stream().anyMatch(value -> value == null || value.isBlank()
                || value.length() > 128) || (kind == ScoreAudienceKind.PUBLIC && !members.isEmpty())) {
            throw new IllegalArgumentException("invalid score audience");
        }
    }

    public static ScoreAudience publicAudience() {
        return new ScoreAudience(ScoreAudienceKind.PUBLIC, Set.of());
    }
}
