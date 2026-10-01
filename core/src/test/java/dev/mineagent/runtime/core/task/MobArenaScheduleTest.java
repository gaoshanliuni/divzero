package dev.mineagent.runtime.core.task;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MobArenaScheduleTest {
    @Test void fiveEvokersAreFiveSimultaneousNativeOpponentsAtSupportedDistinctSpawns() {
        var wave = MobArenaSchedule.wave(0, 5, "evoker", 77);
        assertEquals(5, wave.size());
        assertEquals(wave, MobArenaSchedule.wave(0, 5, "evoker", 77));
        for (var match : wave) {
            assertEquals("ONE_V_NATIVE_MOBS", match.scenario());
            assertEquals("evoker", match.mob()); assertEquals(5, match.enemies());
            assertEquals(5, match.spawns().size());
            for (var a : match.spawns()) {
                assertEquals(12, Math.hypot(a.x(), a.z()), .0001);
                for (var b : match.spawns()) if (a != b) assertTrue(Math.hypot(a.x()-b.x(), a.z()-b.z()) > 1.5);
            }
        }
        assertNotEquals(wave.getFirst().spawns(), MobArenaSchedule.wave(1, 5, "evoker", 77).getFirst().spawns());
        assertThrows(IllegalArgumentException.class, () -> MobArenaSchedule.wave(0, 0, "evoker", 77));
        assertThrows(IllegalArgumentException.class, () -> MobArenaSchedule.wave(0, 5, "player", 77));
    }
}
