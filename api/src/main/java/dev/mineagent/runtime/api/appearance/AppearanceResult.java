package dev.mineagent.runtime.api.appearance;

import java.util.Objects;

public record AppearanceResult(AppearanceStatus status, long revision, String diagnosticCode) {
    public AppearanceResult {
        Objects.requireNonNull(status, "status");
        diagnosticCode = diagnosticCode == null ? "" : diagnosticCode;
    }
}
