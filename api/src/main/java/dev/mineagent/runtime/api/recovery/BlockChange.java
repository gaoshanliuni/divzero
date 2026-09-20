package dev.mineagent.runtime.api.recovery;

import java.util.Objects;

public record BlockChange(BlockSnapshot before, BlockSnapshot after) {
    public BlockChange {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        if (!before.dimension().equals(after.dimension()) || before.x() != after.x()
                || before.y() != after.y() || before.z() != after.z()) {
            throw new IllegalArgumentException("block change coordinates differ");
        }
    }
}
