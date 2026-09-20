package dev.mineagent.runtime.agent.navigation;

public record GridPos(int x, int y, int z) {
    public int manhattanDistance(GridPos other) {
        return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z);
    }

    public GridPos offset(int dx, int dy, int dz) {
        return new GridPos(x + dx, y + dy, z + dz);
    }
}
