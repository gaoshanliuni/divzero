package dev.mineagent.runtime.api.scoreboard;

public record ScoreboardMutationResult(boolean accepted, String errorCode, ScoreboardSnapshot snapshot) {
    public static ScoreboardMutationResult accepted(ScoreboardSnapshot snapshot) {
        return new ScoreboardMutationResult(true, "", snapshot);
    }

    public static ScoreboardMutationResult rejected(String errorCode, ScoreboardSnapshot snapshot) {
        return new ScoreboardMutationResult(false, errorCode, snapshot);
    }
}
