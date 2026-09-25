package dev.mineagent.runtime.neoforge.ui;

import java.util.concurrent.ConcurrentHashMap;

/** Filming-only pauses between existing fixture stages, without replacing their actions. */
public final class CinematicSmokeTiming {
    private record Hold(String stage, long until) {}
    private static final ConcurrentHashMap<String, Hold> HOLDS = new ConcurrentHashMap<>();
    public static boolean pause(String key, Object stage, long milliseconds) {
        if (!Boolean.getBoolean("mineagent.cinematic")) return false;
        String value = String.valueOf(stage);
        long now = System.nanoTime();
        Hold hold = HOLDS.compute(key, (ignored, old) -> old == null || !old.stage().equals(value)
                ? new Hold(value, now + milliseconds * 1_000_000L) : old);
        return now < hold.until();
    }
    private CinematicSmokeTiming() {}
}
