package dev.mineagent.runtime.api.recovery;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ChangeJournalEntry(
        UUID changeId,
        UUID worldId,
        UUID actorId,
        String action,
        List<BlockChange> changes,
        long revision,
        boolean reverted,
        long createdAtEpochMillis
) {
    public ChangeJournalEntry {
        Objects.requireNonNull(changeId, "changeId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(action, "action");
        changes = List.copyOf(changes);
        if (action.isBlank() || revision < 1 || changes.isEmpty()) {
            throw new IllegalArgumentException("invalid change journal entry");
        }
    }
}
