package dev.mineagent.runtime.api.packages;

import java.util.Objects;
import java.util.UUID;

public record RuntimePackageCandidate(
        UUID packageId,
        long packageRevision,
        UUID taskId,
        long taskRevision,
        ActivationMode activationMode,
        String entrypoint,
        String source
) {
    public RuntimePackageCandidate {
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(activationMode, "activationMode");
        Objects.requireNonNull(entrypoint, "entrypoint");
        Objects.requireNonNull(source, "source");
        if (packageRevision < 1 || taskRevision < 0 || entrypoint.isBlank()) {
            throw new IllegalArgumentException("invalid runtime package candidate");
        }
    }
}
