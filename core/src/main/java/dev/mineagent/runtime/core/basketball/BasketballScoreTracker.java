package dev.mineagent.runtime.core.basketball;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class BasketballScoreTracker {
    private static final double RIM_RADIUS = 0.46;
    private static final double THREE_POINT_DISTANCE = 6.75;
    private final Map<UUID, Shot> shots = new LinkedHashMap<>();
    private final Map<UUID, Integer> scores = new LinkedHashMap<>();

    public synchronized void beginShot(UUID shotId, UUID playerId, CourtPoint origin) {
        if (shotId == null || playerId == null || origin == null) {
            throw new IllegalArgumentException("shot metadata is required");
        }
        if (shots.putIfAbsent(shotId, new Shot(playerId, origin, false)) != null) {
            throw new IllegalArgumentException("shot already exists");
        }
    }

    public synchronized BasketballScoreResult observe(
            UUID shotId,
            CourtPoint previous,
            CourtPoint current,
            CourtPoint rimCenter
    ) {
        Shot shot = shots.get(shotId);
        if (shot == null || shot.scored || previous == null || current == null || rimCenter == null
                || current.y() >= previous.y()
                || previous.y() <= rimCenter.y()
                || current.y() > rimCenter.y()) {
            return BasketballScoreResult.noScore();
        }
        double denominator = previous.y() - current.y();
        double fraction = (previous.y() - rimCenter.y()) / denominator;
        if (fraction < 0 || fraction > 1) {
            return BasketballScoreResult.noScore();
        }
        double crossingX = previous.x() + (current.x() - previous.x()) * fraction;
        double crossingZ = previous.z() + (current.z() - previous.z()) * fraction;
        double dx = crossingX - rimCenter.x();
        double dz = crossingZ - rimCenter.z();
        if (dx * dx + dz * dz > RIM_RADIUS * RIM_RADIUS) {
            return BasketballScoreResult.noScore();
        }
        int points = shot.origin.horizontalDistanceTo(rimCenter) >= THREE_POINT_DISTANCE ? 3 : 2;
        int total = scores.merge(shot.playerId, points, Integer::sum);
        shots.put(shotId, new Shot(shot.playerId, shot.origin, true));
        return new BasketballScoreResult(true, points, shot.playerId, total);
    }

    public synchronized int score(UUID playerId) {
        return scores.getOrDefault(playerId, 0);
    }

    public synchronized void clearShot(UUID shotId) {
        shots.remove(shotId);
    }

    private record Shot(UUID playerId, CourtPoint origin, boolean scored) {
    }
}
