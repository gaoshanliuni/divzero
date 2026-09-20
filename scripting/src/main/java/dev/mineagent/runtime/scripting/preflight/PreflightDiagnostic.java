package dev.mineagent.runtime.scripting.preflight;

import java.util.Objects;

public record PreflightDiagnostic(String code, int line, String message) {
    public PreflightDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
