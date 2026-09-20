package dev.mineagent.runtime.core.packages;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record LegacyMigrationReceipt(
        UUID worldId,
        String migrationId,
        Map<UUID, LegacyPackageEvidence> packages,
        long completedAtEpochMillis
) {
    public LegacyMigrationReceipt {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(migrationId, "migrationId");
        packages = Map.copyOf(Objects.requireNonNull(packages, "packages"));
    }
}
