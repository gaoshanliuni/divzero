package dev.mineagent.runtime.core.task;

/** Ongoing attack producers deserve pressure, while immediate protection and safe movement still win. */
public final class CombatSourcePriority {
    public static double bonus(double distance, int observedDependents, boolean summoning, int nextCastTicks) {
        if (!Double.isFinite(distance) || distance < 0 || observedDependents < 0) throw new IllegalArgumentException("COMBAT_SOURCE_OBSERVATION");
        if (!summoning && observedDependents == 0) return 0;
        double pressure = Math.min(14, observedDependents * 2.5 + (summoning ? 4 : 0) + (summoning && nextCastTicks <= 40 ? 3 : 0));
        return pressure * Math.max(0, 1 - distance / 32);
    }
    private CombatSourcePriority() {}
}
