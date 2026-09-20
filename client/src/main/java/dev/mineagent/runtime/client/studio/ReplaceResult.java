package dev.mineagent.runtime.client.studio;

import java.util.Objects;

public record ReplaceResult(String text, int replacements) {
    public ReplaceResult {
        Objects.requireNonNull(text, "text");
        if (replacements < 0) {
            throw new IllegalArgumentException("invalid replacement count");
        }
    }
}
