package dev.mineagent.runtime.api.scoreboard;

import java.util.Objects;

public record NumberFormatSpec(NumberFormatKind kind, String value) {
    public NumberFormatSpec {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        if (value.length() > 2_048
                || ((kind == NumberFormatKind.DEFAULT || kind == NumberFormatKind.BLANK) && !value.isEmpty())
                || ((kind == NumberFormatKind.FIXED || kind == NumberFormatKind.STYLED) && value.isBlank())) {
            throw new IllegalArgumentException("invalid scoreboard number format");
        }
    }

    public static NumberFormatSpec defaultFormat() {
        return new NumberFormatSpec(NumberFormatKind.DEFAULT, "");
    }
}
