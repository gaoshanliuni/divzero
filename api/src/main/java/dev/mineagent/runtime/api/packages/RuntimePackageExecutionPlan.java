package dev.mineagent.runtime.api.packages;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record RuntimePackageExecutionPlan(
        UUID packageId,
        long packageRevision,
        UUID taskId,
        long taskRevision,
        ActivationMode activationMode,
        Map<String, String> modules,
        String entrypoint,
        Set<String> resourceHashes
) {
    public RuntimePackageExecutionPlan {
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(activationMode, "activationMode");
        modules = Map.copyOf(Objects.requireNonNull(modules, "modules"));
        resourceHashes = Set.copyOf(Objects.requireNonNull(resourceHashes, "resourceHashes"));
        if (packageRevision < 1 || taskRevision < 0 || modules.isEmpty() || modules.size() > 64
                || entrypoint == null || !modules.containsKey(entrypoint)) {
            throw new IllegalArgumentException("invalid runtime package execution plan");
        }
        modules.forEach((path, source) -> {
            if (path == null || path.isBlank() || path.length() > 256 || path.contains("..")
                    || path.indexOf('\\') >= 0 || !path.matches("[A-Za-z0-9_.@/-]+")
                    || source == null || source.isBlank() || source.length() > 1_000_000) {
                throw new IllegalArgumentException("invalid runtime module");
            }
        });
        if (resourceHashes.stream().anyMatch(hash -> hash == null || !hash.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("invalid runtime resource hash");
        }
    }
}
