package dev.mineagent.runtime.core.task;

import java.time.Duration;

/** Wall-clock round limits. A new round always needs a fresh human ready action. */
public final class HumanDuelSeries {
    public static final int ROUNDS = 5;
    public static final long LIMIT = Duration.ofMinutes(3).toNanos();
    public enum Phase { READY, COUNTDOWN, STARTING, FIGHTING, BETWEEN, COMPLETE, STOPPED }
    private Phase phase = Phase.READY;
    private int completed;
    private long deadline, started;
    private final int limit;
    public HumanDuelSeries(){this(ROUNDS,0);}
    public HumanDuelSeries(int limit,int completed){if(limit<0||completed<0||limit>0&&completed>limit)throw new IllegalArgumentException("DUEL_ROUNDS");this.limit=limit;this.completed=completed;}
    public Phase phase() { return phase; }
    public int completed() { return completed; }
    public int round() { return completed + 1; }
    public boolean ready(long now) {
        if (phase != Phase.READY && phase != Phase.BETWEEN) return false;
        phase = Phase.COUNTDOWN; deadline = now + Duration.ofSeconds(5).toNanos(); return true;
    }
    public boolean countdownComplete(long now) { return phase == Phase.COUNTDOWN && now >= deadline; }
    public void starting() { if (phase != Phase.COUNTDOWN) throw new IllegalStateException("DUEL_PHASE"); phase = Phase.STARTING; }
    public void started(long now) { if (phase != Phase.STARTING) throw new IllegalStateException("DUEL_PHASE"); phase = Phase.FIGHTING; started = now; deadline = now + LIMIT; }
    public long remainingSeconds(long now) { return Math.max(0, (deadline - now + 999_999_999L) / 1_000_000_000L); }
    public String outcome(long now, boolean humanAlive, boolean aiAlive) {
        if (phase != Phase.FIGHTING) return "";
        if (!humanAlive || !aiAlive) return !humanAlive && !aiAlive ? "DOUBLE_KO" : humanAlive ? "HUMAN_WON" : "AI_WON";
        return now >= deadline ? "TIME_LIMIT_DRAW" : "";
    }
    public double elapsed(long now) { return Math.max(0, (now - started) / 1e9); }
    public boolean finish() {
        if (phase != Phase.FIGHTING) return false;
        completed++; phase = limit>0&&completed == limit ? Phase.COMPLETE : Phase.BETWEEN; return true;
    }
    public void stop() { if (phase != Phase.COMPLETE) phase = Phase.STOPPED; }
}
