package dev.mineagent.runtime.api.scoreboard;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ScoreboardCommand(
        UUID requestId,
        long expectedRevision,
        String action,
        Map<String, String> arguments
) {
    public ScoreboardCommand {
        Objects.requireNonNull(requestId, "requestId");
        if (expectedRevision < 0 || action == null || !action.matches("[a-z][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("invalid scoreboard command");
        }
        arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments"));
        if (arguments.size() > 64 || arguments.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().isBlank() || entry.getKey().length() > 64
                || entry.getValue() == null || entry.getValue().length() > 2_048)) {
            throw new IllegalArgumentException("invalid scoreboard command arguments");
        }
    }
}
