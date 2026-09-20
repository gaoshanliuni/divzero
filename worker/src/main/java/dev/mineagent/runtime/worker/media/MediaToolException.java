package dev.mineagent.runtime.worker.media;

public final class MediaToolException extends RuntimeException {
    public MediaToolException(String message) {
        super(message);
    }

    public MediaToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
