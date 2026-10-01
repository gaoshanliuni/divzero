package dev.mineagent.runtime.core.task;

import java.util.*;

/** 1vNb means one neural actor against N native hostile mobs, not N neural actors. */
public final class MobArenaSchedule {
    public record Spawn(double x, double z) {}
    public record Match(int wave, int lane, String scenario, String mob, int enemies, long seed, List<Spawn> spawns) {}
    public static List<Match> wave(int wave, int enemies, String mob, long seed) {
        if (wave < 0 || enemies < 1 || enemies > 16 || !Set.of("evoker", "zombie", "skeleton", "mixed").contains(mob))
            throw new IllegalArgumentException("MOB_ARENA_SPEC");
        var result = new ArrayList<Match>();
        for (int lane = 0; lane < 5; lane++) {
            var spawns = new ArrayList<Spawn>();
            double offset = (lane * .4 + wave * .2) * Math.PI;
            for (int i = 0; i < enemies; i++) {
                double angle = offset + 2 * Math.PI * i / enemies;
                spawns.add(new Spawn(Math.cos(angle) * 12, Math.sin(angle) * 12));
            }
            result.add(new Match(wave, lane, "ONE_V_NATIVE_MOBS", mob, enemies, seed + wave * 101L + lane * 997L, List.copyOf(spawns)));
        }
        return List.copyOf(result);
    }
    private MobArenaSchedule() {}
}
