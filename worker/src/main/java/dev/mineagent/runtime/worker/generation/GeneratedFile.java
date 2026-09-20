package dev.mineagent.runtime.worker.generation;

import dev.mineagent.runtime.api.packages.RuntimeResourceSide;

import java.util.Objects;

public record GeneratedFile(
        String path,
        RuntimeResourceSide side,
        String mediaType,
        String sha256,
        byte[] content
) {
    public GeneratedFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(sha256, "sha256");
        content = Objects.requireNonNull(content, "content").clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
