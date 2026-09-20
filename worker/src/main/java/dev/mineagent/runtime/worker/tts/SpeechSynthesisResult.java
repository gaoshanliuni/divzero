package dev.mineagent.runtime.worker.tts;

import java.util.Objects;

public record SpeechSynthesisResult(String contentType, byte[] audio) {
    public SpeechSynthesisResult {
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(audio, "audio");
        if (contentType.isBlank() || audio.length == 0) {
            throw new IllegalArgumentException("empty speech result");
        }
        audio = audio.clone();
    }

    @Override
    public byte[] audio() {
        return audio.clone();
    }
}
