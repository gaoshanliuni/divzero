package dev.mineagent.runtime.scripting.packagehost;

import dev.mineagent.runtime.scripting.preflight.PreflightDiagnostic;

import java.util.List;

public record PackageActivationResult(
        boolean activated,
        String errorCode,
        long activePackageRevision,
        Object entrypointResult,
        List<PreflightDiagnostic> diagnostics
) {
    public PackageActivationResult {
        errorCode = errorCode == null ? "" : errorCode;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
