package dev.mineagent.runtime.scripting.studio;

import dev.mineagent.runtime.api.packages.CodeDraft;
import dev.mineagent.runtime.scripting.preflight.PreflightDiagnostic;

import java.util.List;
import java.util.Objects;

public record CodeDraftResult(
        boolean accepted,
        String errorCode,
        CodeDraft draft,
        Object executionResult,
        List<PreflightDiagnostic> diagnostics
) {
    public CodeDraftResult {
        errorCode = errorCode == null ? "" : errorCode;
        Objects.requireNonNull(draft, "draft");
        diagnostics = List.copyOf(diagnostics);
    }

    public static CodeDraftResult rejected(CodeDraft draft, String code, List<PreflightDiagnostic> diagnostics) {
        return new CodeDraftResult(false, code, draft, null, diagnostics);
    }
}
