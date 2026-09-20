package dev.mineagent.runtime.core.persistence;

import java.util.UUID;

public record AuditRecord(
        long sequence,
        UUID worldId,
        String actor,
        String action,
        String target,
        String payload,
        long createdAtEpochMillis
) {
}
