package dev.mineagent.runtime.api.packages;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record GenerationReceipt(
        UUID receiptId,
        UUID requestId,
        UUID taskId,
        UUID packageId,
        UUID instanceId,
        PackageOrigin origin,
        String request,
        String provider,
        String modelReference,
        String rawOutputReference,
        String rawOutputSha256,
        Map<String, String> fileHashes,
        List<String> preflightDiagnostics,
        boolean simulated,
        long createdAtEpochMillis
) {
    public GenerationReceipt {
        Objects.requireNonNull(receiptId, "receiptId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(modelReference, "modelReference");
        Objects.requireNonNull(rawOutputReference, "rawOutputReference");
        if (rawOutputSha256 == null || !rawOutputSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid generation output hash");
        }
        fileHashes = Map.copyOf(Objects.requireNonNull(fileHashes, "fileHashes"));
        preflightDiagnostics = List.copyOf(Objects.requireNonNull(preflightDiagnostics, "preflightDiagnostics"));
        if (request.length() > 65_536 || provider.length() > 128 || modelReference.length() > 256
                || rawOutputReference.length() > 512 || fileHashes.size() > 256
                || preflightDiagnostics.size() > 256) {
            throw new IllegalArgumentException("generation receipt exceeds bounds");
        }
        fileHashes.forEach((path, hash) -> {
            RuntimeEntrypoint.requireRelativePath(path);
            if (hash == null || !hash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid generated file hash");
            }
        });
        if (preflightDiagnostics.stream().anyMatch(value -> value == null || value.length() > 2_048)) {
            throw new IllegalArgumentException("invalid generation diagnostic");
        }
    }
}
