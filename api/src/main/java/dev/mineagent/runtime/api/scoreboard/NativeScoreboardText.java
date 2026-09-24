package dev.mineagent.runtime.api.scoreboard;

/**
 * Observation bounds for modern Minecraft UTF strings, not legacy 16/40-character command limits.
 * Native names are retained verbatim (including whitespace); mutation permissions remain separate.
 */
public final class NativeScoreboardText {
    public static final int MAX_LENGTH = 32_767;

    private NativeScoreboardText() {}

    public static boolean valid(String value) {
        return value != null && value.length() <= MAX_LENGTH;
    }
}
