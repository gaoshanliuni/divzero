package dev.mineagent.runtime.api.config;

import java.util.Map;
import java.util.Objects;

public record ConfigPatch(long expectedRevision, Map<String, String> values) {
    public ConfigPatch {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
        Objects.requireNonNull(values, "values");
        values = Map.copyOf(values);
    }
}
