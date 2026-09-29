package dev.mineagent.runtime.core.task;

import java.util.List;

/** A current attack window is not a stun. Unknown attack timing never creates a free opening. */
public final class CombatOpening {
    public record Attack(String kind, int cooldown, boolean running) {}
    public record Restriction(int remaining, boolean melee, boolean ranged) {}

    public static int availableTicks(List<Attack> attacks, List<Restriction> restrictions, int age) {
        int window = Integer.MAX_VALUE;
        boolean observed = false;
        for (var attack : attacks) {
            if (!attack.running()) continue;
            observed = true;
            int blocked = restrictions.stream().filter(r -> switch (attack.kind()) {
                case "MELEE" -> r.melee();
                case "RANGED" -> r.ranged();
                default -> false;
            }).mapToInt(Restriction::remaining).max().orElse(0);
            // Cooldown and action restrictions are separate facts; either can delay this attack.
            int remaining = Math.max(blocked, Math.max(0, attack.cooldown()));
            window = Math.min(window, Math.max(0, remaining - Math.max(0, age)));
        }
        return observed ? window : 0;
    }

    public static int closingTicks(double distance, double reach, double speedPerTick) {
        return (int) Math.ceil(Math.max(0, distance - reach) / Math.max(.05, speedPerTick));
    }

    public static boolean canCounter(int available, int closing, int ownCooldown, boolean projectileDanger) {
        return !projectileDanger && available >= Math.max(closing, ownCooldown) + 4;
    }

    private CombatOpening() {}
}
