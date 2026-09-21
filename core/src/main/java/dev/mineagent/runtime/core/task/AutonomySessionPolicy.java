package dev.mineagent.runtime.core.task;
/** Resuming a UI pause never retries a failed request or spends another call for unchanged idle state. */
public final class AutonomySessionPolicy {
    private AutonomySessionPolicy(){}
    public static boolean reobserveOnResume(String state,double movedDistanceSquared){
        if ("WAITING_FOR_INSTRUCTION".equals(state)) return false;
        return !"IDLE".equals(state) || !Double.isFinite(movedDistanceSquared) || movedDistanceSquared >= .25;
    }
}
