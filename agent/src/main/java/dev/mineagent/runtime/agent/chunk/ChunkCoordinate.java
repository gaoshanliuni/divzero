package dev.mineagent.runtime.agent.chunk;

import java.util.Objects;

public record ChunkCoordinate(String dimension, int x, int z) {
    public ChunkCoordinate {
        Objects.requireNonNull(dimension, "dimension");
        if (dimension.isBlank()) {
            throw new IllegalArgumentException("dimension must not be blank");
        }
    }
}
