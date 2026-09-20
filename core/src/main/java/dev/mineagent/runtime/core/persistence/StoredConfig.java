package dev.mineagent.runtime.core.persistence;

import java.util.Map;

public record StoredConfig(
        long revision,
        Map<String, String> publicValues,
        Map<String, String> secretValues
) {
    public StoredConfig {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        publicValues = Map.copyOf(publicValues);
        secretValues = Map.copyOf(secretValues);
    }
}
