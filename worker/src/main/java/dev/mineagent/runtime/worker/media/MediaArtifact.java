package dev.mineagent.runtime.worker.media;

import java.util.Objects;

public record MediaArtifact(String sha256, long size, String contentType) {
    public MediaArtifact {
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(contentType, "contentType");
        if (!sha256.matches("[0-9a-f]{64}") || size < 1 || contentType.isBlank()) {
            throw new IllegalArgumentException("invalid media artifact");
        }
    }
}
