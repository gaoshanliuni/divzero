package dev.mineagent.runtime.core.content;

public final class ContentIntegrityException extends RuntimeException {
    public ContentIntegrityException(String message) {
        super(message);
    }

    public ContentIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
