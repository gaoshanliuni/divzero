package dev.mineagent.runtime.core.scoreboard;

import dev.mineagent.runtime.api.scoreboard.ScoreboardPort;

@FunctionalInterface
public interface CustomScoreRule {
    void tick(long tick, ScoreRuleDefinition rule, ScoreboardPort port);
}
