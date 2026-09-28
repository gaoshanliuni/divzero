package dev.mineagent.runtime.core.permission;

import java.util.Map;
import java.util.UUID;

/** Participation is world/player scoped and never grants an administrative capability. */
public final class WorldActivation {
    public enum State { UNDECIDED, ENABLED, DISABLED }
    public static String prefix(UUID world) { return "runtime.activation." + world + "."; }
    public static String key(UUID world, UUID player) { return prefix(world) + player; }
    public static State state(Map<String, String> values, UUID world, UUID player) {
        String choice = values.get(key(world, player));
        if (choice != null) {
            try { return State.valueOf(choice); }
            catch (IllegalArgumentException invalid) { return State.UNDECIDED; }
        }
        // The legacy global runtime.initialized flag cannot prove consent for this world.
        return State.UNDECIDED;
    }
    private WorldActivation() {}
}
