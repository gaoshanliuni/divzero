package dev.mineagent.runtime.api.memory;

import java.util.Objects;
import java.util.UUID;

public record MemoryEntry(
        UUID memoryId,
        UUID worldId,
        UUID ownerPlayerId,
        MemoryKind kind,
        String key,
        String value,
        long revision,
        long updatedAtEpochMillis
) {
    public MemoryEntry {
        Objects.requireNonNull(memoryId, "memoryId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
    }
}
