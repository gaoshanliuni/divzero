package dev.mineagent.runtime.api.scoreboard;

public interface ScoreboardPort {
    ScoreboardSnapshot snapshot();

    ScoreboardMutationResult createObjective(
            String name,
            String criteria,
            String displayName,
            String renderType,
            boolean autoUpdate,
            NumberFormatSpec numberFormat
    );

    ScoreboardMutationResult removeObjective(String name);

    ScoreboardMutationResult updateObjective(
            String name,
            String displayName,
            String renderType,
            boolean autoUpdate,
            NumberFormatSpec numberFormat
    );

    ScoreboardMutationResult setScore(String objectiveName, String holder, int value);

    ScoreboardMutationResult addScore(String objectiveName, String holder, int delta);

    ScoreboardMutationResult resetScore(String objectiveName, String holder);

    ScoreboardMutationResult resetHolder(String holder);

    ScoreboardMutationResult setScoreDisplay(
            String objectiveName,
            String holder,
            String displayName,
            NumberFormatSpec numberFormat
    );

    ScoreboardMutationResult operate(
            String targetObjective,
            String targetHolder,
            ScoreOperation operation,
            String sourceObjective,
            String sourceHolder
    );

    ScoreboardMutationResult setDisplaySlot(String slot, String objectiveName);
}
