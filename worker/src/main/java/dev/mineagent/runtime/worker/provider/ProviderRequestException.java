package dev.mineagent.runtime.worker.provider;

public final class ProviderRequestException extends RuntimeException {
    private final int statusCode;

    public ProviderRequestException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public ProviderRequestException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    public int statusCode() {
        return statusCode;
    }
}
