package dev.mineagent.runtime.api.scoreboard;

import java.util.Objects;
import java.util.UUID;

public record ScoreboardCommandResult(
        UUID requestId,
        boolean accepted,
        String errorCode,
        long revision,
        ScoreboardSnapshot snapshot
) {
    public ScoreboardCommandResult {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(errorCode, "errorCode");
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
