package dev.mineagent.runtime.worker.compile;

public record CompilationDiagnostic(String kind, long line, long column, String message) {
}
