package dev.mineagent.runtime.api.packages;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ContentPackage(
        UUID packageId,
        String name,
        String version,
        ActivationMode activationMode,
        Map<UUID, String> dependencies,
        Set<String> permissions,
        String source,
        String sha256,
        String signature,
        boolean enabled,
        long revision,
        long updatedAtEpochMillis
) {
    public ContentPackage {
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(activationMode, "activationMode");
        dependencies = Map.copyOf(dependencies);
        permissions = Set.copyOf(permissions);
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(signature, "signature");
    }

    public ContentPackage withSource(String value) {
        return new ContentPackage(packageId, name, version, activationMode, dependencies, permissions,
                value, sha256, signature, enabled, revision, updatedAtEpochMillis);
    }

    public ContentPackage withSignature(String value) {
        return new ContentPackage(packageId, name, version, activationMode, dependencies, permissions,
                source, sha256, value, enabled, revision, updatedAtEpochMillis);
    }
}
