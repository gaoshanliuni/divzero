package dev.mineagent.runtime.core.basketball;

import java.util.UUID;

public record BasketballScoreResult(boolean scored, int points, UUID playerId, int totalScore) {
    public static BasketballScoreResult noScore() {
        return new BasketballScoreResult(false, 0, null, 0);
    }
}
