package dev.mineagent.runtime.api.recovery;

import java.util.Objects;

public record BlockSnapshot(String dimension, int x, int y, int z, String state, String blockEntitySnbt) {
    public BlockSnapshot {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(state, "state");
        if (dimension.isBlank() || state.isBlank()) {
            throw new IllegalArgumentException("invalid block snapshot");
        }
        blockEntitySnbt = blockEntitySnbt == null ? "" : blockEntitySnbt;
    }

    public BlockSnapshot(String dimension, int x, int y, int z, String state) {
        this(dimension, x, y, z, state, "");
    }
}
