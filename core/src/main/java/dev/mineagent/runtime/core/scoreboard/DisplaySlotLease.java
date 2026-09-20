package dev.mineagent.runtime.core.scoreboard;

import java.util.Objects;
import java.util.UUID;

public record DisplaySlotLease(
        UUID viewId,
        String slot,
        String previousObjective,
        String claimedObjective,
        boolean active,
        long revision,
        long updatedAtEpochMillis
) {
    public DisplaySlotLease {
        Objects.requireNonNull(viewId, "viewId");
        if (slot == null || slot.isBlank() || slot.length() > 64 || previousObjective == null
                || claimedObjective == null || claimedObjective.isBlank() || revision < 1) {
            throw new IllegalArgumentException("invalid display slot lease");
        }
    }
}
