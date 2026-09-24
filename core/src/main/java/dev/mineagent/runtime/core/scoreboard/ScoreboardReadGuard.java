package dev.mineagent.runtime.core.scoreboard;

import java.util.function.Consumer;

/** Isolates optional read/projection ticks. Never use for retrying world mutations or model requests. */
public final class ScoreboardReadGuard {
    private long retryAt;
    private long reportAt;
    private boolean unavailable;
    private RuntimeException lastFailure;

    public boolean run(long tick, Runnable observation, Consumer<RuntimeException> report) {
        if (tick < retryAt) return false;
        try {
            observation.run();
            unavailable = false;
            lastFailure = null;
            return true;
        } catch (RuntimeException failure) {
            lastFailure = failure;
            retryAt = after(tick, 100);
            boolean shouldReport = !unavailable || tick >= reportAt;
            unavailable = true;
            if (shouldReport) {
                reportAt = after(tick, 1200);
                try {
                    report.accept(failure);
                } catch (RuntimeException reportingFailure) {
                    if (reportingFailure != failure) failure.addSuppressed(reportingFailure);
                }
            }
            return false;
        }
    }

    private static long after(long tick, long interval) {
        return tick > Long.MAX_VALUE - interval ? Long.MAX_VALUE : tick + interval;
    }

    public boolean unavailable() { return unavailable; }
    public RuntimeException lastFailure() { return lastFailure; }
}
