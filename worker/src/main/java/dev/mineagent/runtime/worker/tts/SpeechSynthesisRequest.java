package dev.mineagent.runtime.worker.tts;

import java.util.Objects;

public record SpeechSynthesisRequest(String text, String voice, String rate, String pitch, String volume) {
    public SpeechSynthesisRequest {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(voice, "voice");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(pitch, "pitch");
        Objects.requireNonNull(volume, "volume");
        text = text.strip();
        if (text.isBlank() || text.codePointCount(0, text.length()) > 16_384) {
            throw new IllegalArgumentException("invalid speech text");
        }
    }
}
