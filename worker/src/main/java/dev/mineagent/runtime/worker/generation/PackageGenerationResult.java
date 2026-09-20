package dev.mineagent.runtime.worker.generation;

import dev.mineagent.runtime.core.content.StoredObject;

import java.util.List;

public record PackageGenerationResult(
        boolean success,
        String errorCode,
        String diagnostic,
        String providerId,
        String rawOutput,
        ParsedRuntimePackage runtimePackage,
        List<StoredObject> storedFiles
) {
    public PackageGenerationResult {
        storedFiles = List.copyOf(storedFiles);
    }
}
