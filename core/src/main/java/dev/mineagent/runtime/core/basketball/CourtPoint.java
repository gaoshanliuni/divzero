package dev.mineagent.runtime.core.basketball;

public record CourtPoint(double x, double y, double z) {
    public double horizontalDistanceTo(CourtPoint other) {
        double dx = x - other.x;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
