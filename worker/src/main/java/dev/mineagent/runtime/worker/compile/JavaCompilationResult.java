package dev.mineagent.runtime.worker.compile;

import java.nio.file.Path;
import java.util.List;

public record JavaCompilationResult(boolean success, Path jarPath, List<CompilationDiagnostic> diagnostics) {
    public JavaCompilationResult {
        diagnostics = List.copyOf(diagnostics);
    }
}
