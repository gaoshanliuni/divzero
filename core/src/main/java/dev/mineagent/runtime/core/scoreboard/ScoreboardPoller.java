package dev.mineagent.runtime.core.scoreboard;

import dev.mineagent.runtime.api.scoreboard.ScoreEntrySnapshot;
import dev.mineagent.runtime.api.scoreboard.ScoreObjectiveSnapshot;
import dev.mineagent.runtime.api.scoreboard.ScoreboardPort;
import dev.mineagent.runtime.api.scoreboard.ScoreboardSnapshot;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class ScoreboardPoller {
    private final ScoreboardPort port;
    private final int intervalTicks;
    private final Consumer<ScoreboardDelta> consumer;
    private ScoreboardSnapshot previous;
    private long lastPollTick;
    private long revision;

    public ScoreboardPoller(ScoreboardPort port, int intervalTicks, Consumer<ScoreboardDelta> consumer) {
        if (port == null || intervalTicks < 1 || intervalTicks > 1_200 || consumer == null) {
            throw new IllegalArgumentException("invalid scoreboard poller");
        }
        this.port = port;
        this.intervalTicks = intervalTicks;
        this.consumer = consumer;
    }

    public synchronized void tick(long tick) {
        if (tick < 0 || (previous != null && tick < lastPollTick)) {
            throw new IllegalArgumentException("scoreboard poll tick cannot move backwards");
        }
        if (previous == null) {
            previous = port.snapshot();
            lastPollTick = tick;
            return;
        }
        if (tick - lastPollTick < intervalTicks) {
            return;
        }
        ScoreboardSnapshot current = port.snapshot();
        lastPollTick = tick;
        Set<String> changed = changedObjectives(previous, current);
        previous = current;
        if (!changed.isEmpty()) {
            consumer.accept(new ScoreboardDelta(++revision, false, changed, current));
        }
    }

    public synchronized ScoreboardDelta fullSnapshot() {
        ScoreboardSnapshot snapshot = port.snapshot();
        previous = snapshot;
        return new ScoreboardDelta(++revision, true,
                snapshot.objectives().stream().map(ScoreObjectiveSnapshot::name)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()), snapshot);
    }

    private static Set<String> changedObjectives(ScoreboardSnapshot previous, ScoreboardSnapshot current) {
        Map<String, Object> before = byObjective(previous);
        Map<String, Object> after = byObjective(current);
        var names = new HashSet<String>();
        names.addAll(before.keySet());
        names.addAll(after.keySet());
        names.removeIf(name -> java.util.Objects.equals(before.get(name), after.get(name)));
        if (!previous.displaySlots().equals(current.displaySlots())) {
            names.addAll(previous.displaySlots().values());
            names.addAll(current.displaySlots().values());
            names.remove("");
        }
        return Set.copyOf(names);
    }

    private static Map<String, Object> byObjective(ScoreboardSnapshot snapshot) {
        var values = new LinkedHashMap<String, Object>();
        snapshot.objectives().stream().sorted(java.util.Comparator.comparing(ScoreObjectiveSnapshot::name))
                .forEach(objective -> values.put(objective.name(), new ObjectiveValue(objective,
                        snapshot.entries().stream()
                                .filter(entry -> entry.objectiveName().equals(objective.name()))
                                .sorted(java.util.Comparator.comparing(ScoreEntrySnapshot::holder))
                                .toList())));
        return values;
    }

    private record ObjectiveValue(ScoreObjectiveSnapshot objective, java.util.List<ScoreEntrySnapshot> entries) {
    }
}
