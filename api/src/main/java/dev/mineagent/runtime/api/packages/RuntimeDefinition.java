package dev.mineagent.runtime.api.packages;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record RuntimeDefinition(
        UUID definitionId,
        String name,
        RuntimeDefinitionKind kind,
        String entrypointId,
        Set<String> resourcePaths,
        Map<String, String> settingsSchema,
        long revision,
        int stateSchemaVersion,
        String migrationEntrypointId
) {
    public RuntimeDefinition(
            UUID definitionId,
            String name,
            RuntimeDefinitionKind kind,
            String entrypointId,
            Set<String> resourcePaths,
            Map<String, String> settingsSchema,
            long revision
    ) {
        this(definitionId, name, kind, entrypointId, resourcePaths, settingsSchema, revision, 1, null);
    }

    public RuntimeDefinition {
        Objects.requireNonNull(definitionId, "definitionId");
        if (name == null || name.isBlank() || name.length() > 128) {
            throw new IllegalArgumentException("invalid definition name");
        }
        Objects.requireNonNull(kind, "kind");
        if (entrypointId == null || entrypointId.isBlank() || entrypointId.length() > 64) {
            throw new IllegalArgumentException("invalid definition entrypoint");
        }
        resourcePaths = Set.copyOf(Objects.requireNonNull(resourcePaths, "resourcePaths"));
        resourcePaths.forEach(RuntimeEntrypoint::requireRelativePath);
        settingsSchema = Map.copyOf(Objects.requireNonNull(settingsSchema, "settingsSchema"));
        if (revision < 1 || stateSchemaVersion < 1) {
            throw new IllegalArgumentException("invalid definition revision");
        }
        if (migrationEntrypointId != null
                && (migrationEntrypointId.isBlank() || migrationEntrypointId.length() > 64)) {
            throw new IllegalArgumentException("invalid migration entrypoint");
        }
    }
}
