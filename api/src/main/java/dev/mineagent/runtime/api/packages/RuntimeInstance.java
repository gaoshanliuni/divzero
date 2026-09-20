package dev.mineagent.runtime.api.packages;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record RuntimeInstance(
        UUID instanceId,
        UUID worldId,
        UUID packageId,
        UUID definitionId,
        long definitionRevision,
        RuntimeInstanceLocation location,
        Map<String, String> state,
        int stateSchemaVersion,
        long revision,
        long updatedAtEpochMillis
) {
    public RuntimeInstance {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(location, "location");
        state = Map.copyOf(Objects.requireNonNull(state, "state"));
        if (definitionRevision < 1 || stateSchemaVersion < 1 || revision < 1) {
            throw new IllegalArgumentException("invalid instance revision");
        }
        if (state.size() > 256) {
            throw new IllegalArgumentException("runtime instance state exceeds entry limit");
        }
        long stateBytes = 0;
        for (var entry : state.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getKey().length() > 128
                    || entry.getValue() == null || entry.getValue().length() > 65_536) {
                throw new IllegalArgumentException("invalid runtime instance state");
            }
            stateBytes += entry.getKey().length() + entry.getValue().length();
        }
        if (stateBytes > 1_048_576) {
            throw new IllegalArgumentException("runtime instance state exceeds size limit");
        }
    }
}
