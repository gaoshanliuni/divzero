package dev.mineagent.runtime.core.packages;

import java.util.Objects;

public record LegacyPackageEvidence(
        String legacySha256,
        String legacySignature,
        long legacyRevision,
        String migratedEntrypoint
) {
    public LegacyPackageEvidence {
        Objects.requireNonNull(legacySha256, "legacySha256");
        Objects.requireNonNull(legacySignature, "legacySignature");
        Objects.requireNonNull(migratedEntrypoint, "migratedEntrypoint");
    }
}
