package dev.mineagent.runtime.core.persistence;

import java.util.UUID;

public record RuntimeRecord(
        UUID worldId,
        String namespace,
        String recordId,
        long revision,
        String payload,
        long updatedAtEpochMillis,
        boolean deleted
) {
}
