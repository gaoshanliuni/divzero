package dev.mineagent.runtime.core.scoreboard;

import dev.mineagent.runtime.api.scoreboard.ScoreboardMutationResult;
import dev.mineagent.runtime.api.scoreboard.ScoreboardPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public final class ScoreRuleEngine {
    private static final int MAX_RULES = 256;
    private static final int MAX_DIAGNOSTICS = 256;
    private final ScoreboardPort port;
    private final Function<UUID, ScoreSourceBinding> sources;
    private final Map<UUID, ScoreRuleDefinition> rules = new LinkedHashMap<>();
    private final Map<UUID, Long> lastTicks = new LinkedHashMap<>();
    private final Map<UUID, CustomScoreRule> customRules = new LinkedHashMap<>();
    private final Set<UUID> paused = new java.util.HashSet<>();
    private final Set<UUID> failed = new java.util.HashSet<>();
    private final List<String> diagnostics = new ArrayList<>();
    private long currentTick;

    public ScoreRuleEngine(ScoreboardPort port, Function<UUID, ScoreSourceBinding> sources) {
        this.port = java.util.Objects.requireNonNull(port, "port");
        this.sources = java.util.Objects.requireNonNull(sources, "sources");
    }

    public synchronized void register(ScoreRuleDefinition rule) {
        if (rule == null || (rules.size() >= MAX_RULES && !rules.containsKey(rule.ruleId()))) {
            throw new IllegalArgumentException("score rule limit reached");
        }
        ScoreRuleDefinition previous = rules.get(rule.ruleId());
        if (previous != null && rule.revision() <= previous.revision()) {
            throw new IllegalArgumentException("stale score rule revision");
        }
        requireWritableSource(rule.outputSourceId());
        rules.put(rule.ruleId(), rule);
        lastTicks.put(rule.ruleId(), currentTick);
        failed.remove(rule.ruleId());
    }

    public synchronized void registerCustom(UUID ruleId, CustomScoreRule implementation) {
        customRules.put(ruleId, java.util.Objects.requireNonNull(implementation, "implementation"));
    }

    public synchronized void setPaused(UUID ruleId, boolean value) {
        if (!rules.containsKey(ruleId)) {
            throw new IllegalArgumentException("unknown score rule");
        }
        if (value) {
            paused.add(ruleId);
        } else {
            paused.remove(ruleId);
        }
    }

    public synchronized void tick(long tick) {
        if (tick < currentTick) {
            throw new IllegalArgumentException("score rule tick cannot move backwards");
        }
        currentTick = tick;
        for (ScoreRuleDefinition rule : List.copyOf(rules.values())) {
            if (!rule.enabled() || failed.contains(rule.ruleId())) {
                continue;
            }
            if (paused.contains(rule.ruleId())) {
                lastTicks.put(rule.ruleId(), tick);
                continue;
            }
            int interval = intConfig(rule, "intervalTicks", 10, 1, 72_000);
            long previous = lastTicks.getOrDefault(rule.ruleId(), tick);
            long elapsed = tick - previous;
            if (elapsed < interval) {
                continue;
            }
            long executions = Math.min(100, elapsed / interval);
            lastTicks.put(rule.ruleId(), previous + executions * interval);
            try {
                switch (rule.kind()) {
                    case TIMER -> applyTimer(rule, executions);
                    case TEAM_TOTAL -> applyTeamTotal(rule);
                    case CUSTOM -> {
                        CustomScoreRule custom = customRules.get(rule.ruleId());
                        if (custom == null) {
                            throw new IllegalStateException("custom score rule implementation is unavailable");
                        }
                        custom.tick(tick, rule, port);
                    }
                }
            } catch (RuntimeException failure) {
                failed.add(rule.ruleId());
                diagnostics.add(rule.ruleId() + ":" + stableMessage(failure));
                while (diagnostics.size() > MAX_DIAGNOSTICS) {
                    diagnostics.removeFirst();
                }
            }
        }
    }

    public synchronized List<String> diagnostics() {
        return List.copyOf(diagnostics);
    }

    private void applyTimer(ScoreRuleDefinition rule, long executions) {
        ScoreSourceBinding output = requireWritableSource(rule.outputSourceId());
        int step = intConfig(rule, "step", 1, -1_000_000, 1_000_000);
        String unit = rule.config().getOrDefault("unit", "TICKS");
        if (!unit.equals("TICKS") && !unit.equals("SECONDS")) {
            throw new IllegalArgumentException("timer unit must be TICKS or SECONDS");
        }
        int delta = Math.toIntExact(Math.multiplyExact((long) step, executions));
        requireAccepted(port.addScore(output.reference(), required(rule, "holder"), delta));
    }

    private void applyTeamTotal(ScoreRuleDefinition rule) {
        if (rule.inputSourceIds().size() != 1) {
            throw new IllegalArgumentException("team total requires one input source");
        }
        ScoreSourceBinding input = requireSource(rule.inputSourceIds().getFirst());
        ScoreSourceBinding output = requireWritableSource(rule.outputSourceId());
        String teamName = required(rule, "team");
        var snapshot = port.snapshot();
        var team = snapshot.teams().stream().filter(value -> value.name().equals(teamName))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("score team is unavailable"));
        int total = 0;
        for (var entry : snapshot.entries()) {
            if (entry.objectiveName().equals(input.reference()) && team.members().contains(entry.holder())) {
                total = Math.addExact(total, entry.score());
            }
        }
        requireAccepted(port.setScore(output.reference(), required(rule, "holder"), total));
    }

    private ScoreSourceBinding requireWritableSource(UUID id) {
        ScoreSourceBinding source = requireSource(id);
        if (source.accessMode() != ScoreAccessMode.READ_WRITE) {
            throw new IllegalArgumentException("score rule output is read-only");
        }
        return source;
    }

    private ScoreSourceBinding requireSource(UUID id) {
        ScoreSourceBinding source = sources.apply(id);
        if (source == null) {
            throw new IllegalArgumentException("score rule source is unavailable");
        }
        return source;
    }

    private static void requireAccepted(ScoreboardMutationResult result) {
        if (!result.accepted()) {
            throw new IllegalStateException(result.errorCode());
        }
    }

    private static String required(ScoreRuleDefinition rule, String key) {
        String value = rule.config().get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing score rule config " + key);
        }
        return value;
    }

    private static int intConfig(ScoreRuleDefinition rule, String key, int fallback, int minimum, int maximum) {
        String value = rule.config().get(key);
        if (value == null) {
            return fallback;
        }
        int parsed = Integer.parseInt(value);
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException("score rule config out of range: " + key);
        }
        return parsed;
    }

    private static String stableMessage(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
