package dev.mineagent.runtime.api.config;

import java.util.Map;
import java.util.Objects;

public record PanelSnapshot(long revision, Map<String, String> values) {
    public static final String SECRET_CONFIGURED = "__CONFIGURED__";

    public PanelSnapshot {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        Objects.requireNonNull(values, "values");
        values = Map.copyOf(values);
    }
}
