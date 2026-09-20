package dev.mineagent.runtime.worker.generation;

public final class PackageOutputException extends RuntimeException {
    private final String code;

    public PackageOutputException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
