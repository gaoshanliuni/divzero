package dev.mineagent.runtime.api.packages;

import java.util.Objects;

public record RuntimeResourceRef(
        String path,
        String sha256,
        RuntimeResourceSide side,
        String mediaType,
        long size
) {
    public RuntimeResourceRef {
        RuntimeEntrypoint.requireRelativePath(path);
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid resource sha256");
        }
        Objects.requireNonNull(side, "side");
        if (mediaType == null || mediaType.isBlank() || mediaType.length() > 128 || size < 0) {
            throw new IllegalArgumentException("invalid resource metadata");
        }
    }
}
