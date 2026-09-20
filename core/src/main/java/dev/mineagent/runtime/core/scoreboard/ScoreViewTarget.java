package dev.mineagent.runtime.core.scoreboard;

public record ScoreViewTarget(
        String target,
        String dimension,
        double x,
        double y,
        double z,
        float yaw,
        float scale
) {
    public ScoreViewTarget {
        if (target == null || target.isBlank() || target.length() > 128 || dimension == null
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(scale) || scale <= 0 || scale > 32) {
            throw new IllegalArgumentException("invalid score view target");
        }
    }

    public static ScoreViewTarget hud(String anchor) {
        return new ScoreViewTarget(anchor, "", 0, 0, 0, 0, 1);
    }

    public static ScoreViewTarget world(
            String dimension,
            double x,
            double y,
            double z,
            float yaw,
            float scale
    ) {
        if (dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("invalid score view dimension");
        }
        return new ScoreViewTarget("WORLD", dimension, x, y, z, yaw, scale);
    }
}
