package dev.mineagent.runtime.api.recovery;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record LocalSnapshot(
        UUID snapshotId,
        UUID worldId,
        UUID ownerPlayerId,
        String label,
        long revision,
        List<BlockSnapshot> blocks,
        long createdAtEpochMillis,
        long expiresAtEpochMillis,
        long estimatedBytes
) {
    public LocalSnapshot {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        Objects.requireNonNull(label, "label");
        blocks = List.copyOf(blocks);
    }
}
