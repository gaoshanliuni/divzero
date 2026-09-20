package dev.mineagent.runtime.client.scoreboard;

import dev.mineagent.runtime.api.scoreboard.ScoreViewSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class ScoreHudState {
    private static final int MAX_VIEWS = 64;
    private final LinkedHashMap<UUID, ScoreViewSnapshot> snapshots = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, Long> revisions = new LinkedHashMap<>();

    public synchronized boolean accept(ScoreViewSnapshot snapshot) {
        long current = revisions.getOrDefault(snapshot.viewId(), 0L);
        if (snapshot.revision() <= current) {
            return false;
        }
        revisions.put(snapshot.viewId(), snapshot.revision());
        snapshots.put(snapshot.viewId(), snapshot);
        trim();
        return true;
    }

    public synchronized boolean remove(UUID viewId, long revision) {
        long current = revisions.getOrDefault(viewId, 0L);
        if (revision <= current) {
            return false;
        }
        revisions.put(viewId, revision);
        snapshots.remove(viewId);
        trim();
        return true;
    }

    public synchronized Optional<ScoreViewSnapshot> snapshot(UUID viewId) {
        return Optional.ofNullable(snapshots.get(viewId));
    }

    public synchronized java.util.List<ScoreViewSnapshot> snapshots() {
        return java.util.List.copyOf(snapshots.values());
    }

    public synchronized Optional<ScoreHudLayout> layout(UUID viewId, int screenWidth, int screenHeight) {
        ScoreViewSnapshot snapshot = snapshots.get(viewId);
        if (snapshot == null || screenWidth < 1 || screenHeight < 1) {
            return Optional.empty();
        }
        Map<String, String> values = snapshot.layout();
        int width = boundedInt(values.get("width"), 140, 64, Math.max(64, screenWidth));
        int topN = boundedInt(values.get("topN"), 10, 1, 100);
        int page = boundedInt(values.get("page"), 0, 0, 1_000);
        float scale = boundedFloat(values.get("scale"), 1, 0.25F, 4);
        int start = Math.min(snapshot.rows().size(), Math.multiplyExact(page, topN));
        int end = Math.min(snapshot.rows().size(), start + topN);
        var rows = snapshot.rows().subList(start, end);
        int height = Math.round((rows.size() + 1) * 10 * scale);
        int margin = 8;
        String anchor = values.getOrDefault("anchor", "TOP_RIGHT");
        int x = switch (anchor) {
            case "TOP_LEFT", "BOTTOM_LEFT" -> margin;
            default -> Math.max(margin, screenWidth - width - margin);
        };
        int y = switch (anchor) {
            case "BOTTOM_LEFT", "BOTTOM_RIGHT" -> Math.max(margin, screenHeight - height - margin);
            default -> margin;
        };
        return Optional.of(new ScoreHudLayout(x, y, width, scale, snapshot.title(), rows));
    }

    private void trim() {
        while (revisions.size() > MAX_VIEWS) {
            UUID oldest = revisions.keySet().iterator().next();
            revisions.remove(oldest);
            snapshots.remove(oldest);
        }
    }

    private static int boundedInt(String value, int fallback, int minimum, int maximum) {
        try {
            return Math.clamp(Integer.parseInt(value), minimum, maximum);
        } catch (RuntimeException invalid) {
            return fallback;
        }
    }

    private static float boundedFloat(String value, float fallback, float minimum, float maximum) {
        try {
            return Math.clamp(Float.parseFloat(value), minimum, maximum);
        } catch (RuntimeException invalid) {
            return fallback;
        }
    }
}
