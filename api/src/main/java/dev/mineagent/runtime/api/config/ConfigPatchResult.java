package dev.mineagent.runtime.api.config;

import java.util.Map;
import java.util.Objects;

public record ConfigPatchResult(
        boolean accepted,
        PanelSnapshot snapshot,
        String errorCode,
        Map<String, String> fieldErrors
) {
    public ConfigPatchResult {
        Objects.requireNonNull(snapshot, "snapshot");
        errorCode = errorCode == null ? "" : errorCode;
        fieldErrors = fieldErrors == null ? Map.of() : Map.copyOf(fieldErrors);
    }

    public static ConfigPatchResult accepted(PanelSnapshot snapshot) {
        return new ConfigPatchResult(true, snapshot, "", Map.of());
    }

    public static ConfigPatchResult rejected(PanelSnapshot snapshot, String errorCode) {
        return new ConfigPatchResult(false, snapshot, errorCode, Map.of());
    }

    public static ConfigPatchResult rejected(
            PanelSnapshot snapshot,
            String errorCode,
            Map<String, String> fieldErrors
    ) {
        return new ConfigPatchResult(false, snapshot, errorCode, fieldErrors);
    }
}
