package dev.mineagent.runtime.scripting.preflight;

import java.util.List;

public record PreflightResult(boolean accepted, List<PreflightDiagnostic> diagnostics) {
    public PreflightResult {
        diagnostics = List.copyOf(diagnostics);
    }
}
