package dev.mineagent.runtime.agent.chunk;

import java.util.Set;

public record TicketDelta(
        boolean accepted,
        String errorCode,
        Set<ChunkCoordinate> added,
        Set<ChunkCoordinate> removed
) {
    public TicketDelta {
        errorCode = errorCode == null ? "" : errorCode;
        added = Set.copyOf(added);
        removed = Set.copyOf(removed);
    }

    public static TicketDelta rejected(String code) {
        return new TicketDelta(false, code, Set.of(), Set.of());
    }
}
