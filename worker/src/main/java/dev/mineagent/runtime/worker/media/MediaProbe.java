package dev.mineagent.runtime.worker.media;

import java.util.Objects;

public record MediaProbe(String format, long durationMillis, int width, int height,
                         boolean hasVideo, boolean hasAudio) {
    public MediaProbe {
        format = Objects.requireNonNullElse(format, "");
        if (durationMillis < 0 || width < 0 || height < 0) {
            throw new IllegalArgumentException("invalid media probe");
        }
    }
}
