package dev.mineagent.runtime.core.scoreboard;

import dev.mineagent.runtime.api.scoreboard.ScoreRow;
import dev.mineagent.runtime.api.scoreboard.ScoreViewSnapshot;
import dev.mineagent.runtime.api.scoreboard.ScoreboardSnapshot;

import java.util.Comparator;

public final class ScoreboardViewProjector {
    public ScoreViewSnapshot project(
            ScoreView view,
            ScoreSourceBinding source,
            ScoreboardSnapshot scoreboard,
            long revision
    ) {
        if (!view.sourceId().equals(source.sourceId())) {
            throw new IllegalArgumentException("score view source mismatch");
        }
        var objective = scoreboard.objectives().stream()
                .filter(value -> value.name().equals(source.reference())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("score objective is unavailable"));
        Comparator<dev.mineagent.runtime.api.scoreboard.ScoreEntrySnapshot> order = Comparator
                .comparingInt(dev.mineagent.runtime.api.scoreboard.ScoreEntrySnapshot::score)
                .thenComparing(dev.mineagent.runtime.api.scoreboard.ScoreEntrySnapshot::holder);
        if (!"ASC".equalsIgnoreCase(view.layout().getOrDefault("sort", "DESC"))) {
            order = order.reversed();
        }
        int topN = boundedInt(view.layout().get("topN"), 10, 1, 100);
        int page = boundedInt(view.layout().get("page"), 0, 0, 1_000);
        var ordered = scoreboard.entries().stream()
                .filter(entry -> entry.objectiveName().equals(source.reference()))
                .sorted(order).toList();
        int start = Math.min(ordered.size(), page * topN);
        int end = Math.min(ordered.size(), start + topN);
        String format = view.layout().getOrDefault("numberFormat", "INTEGER");
        String icon = view.layout().getOrDefault("iconSha256", "");
        var rows = ordered.subList(start, end).stream().map(entry -> new ScoreRow(
                entry.holder(), entry.displayName().isEmpty() ? entry.holder() : entry.displayName(),
                entry.score(), format(entry.score(), format), icon)).toList();
        return new ScoreViewSnapshot(view.viewId(), revision, true,
                view.layout().getOrDefault("title", objective.displayName()), rows, view.layout());
    }

    private static String format(int value, String format) {
        if (!"TICKS_TIME".equalsIgnoreCase(format)) {
            return Integer.toString(value);
        }
        long ticks = Math.max(0L, value);
        long minutes = ticks / 1_200;
        long seconds = ticks / 20 % 60;
        long hundredths = ticks % 20 * 5;
        return "%d:%02d.%02d".formatted(minutes, seconds, hundredths);
    }

    private static int boundedInt(String value, int fallback, int minimum, int maximum) {
        try {
            return Math.clamp(Integer.parseInt(value), minimum, maximum);
        } catch (RuntimeException invalid) {
            return fallback;
        }
    }
}
