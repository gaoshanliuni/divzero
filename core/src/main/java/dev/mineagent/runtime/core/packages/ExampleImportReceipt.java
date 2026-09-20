package dev.mineagent.runtime.core.packages;

import java.util.Objects;
import java.util.UUID;

public record ExampleImportReceipt(
        String exampleId,
        UUID packageId,
        String sourceCanonicalSha256,
        long importedAtEpochMillis
) {
    public ExampleImportReceipt {
        if (exampleId == null || exampleId.isBlank() || exampleId.length() > 128) {
            throw new IllegalArgumentException("invalid example id");
        }
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(sourceCanonicalSha256, "sourceCanonicalSha256");
    }
}
