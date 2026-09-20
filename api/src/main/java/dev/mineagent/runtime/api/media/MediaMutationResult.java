package dev.mineagent.runtime.api.media;

public record MediaMutationResult(boolean accepted, String errorCode, MediaEntry entry) {
    public MediaMutationResult {
        errorCode = errorCode == null ? "" : errorCode;
    }

    public static MediaMutationResult accepted(MediaEntry entry) {
        return new MediaMutationResult(true, "", entry);
    }

    public static MediaMutationResult rejected(MediaEntry entry, String code) {
        return new MediaMutationResult(false, code, entry);
    }
}
