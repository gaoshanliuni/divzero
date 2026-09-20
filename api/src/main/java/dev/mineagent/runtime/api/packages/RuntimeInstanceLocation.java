package dev.mineagent.runtime.api.packages;

public record RuntimeInstanceLocation(
        String dimension,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {
    public RuntimeInstanceLocation {
        if (dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("invalid instance location");
        }
    }
}
