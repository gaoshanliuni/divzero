package dev.mineagent.runtime.core.task;

/** A pursuit limit must not trap an actor outside it after an emergency withdrawal. */
public final class CombatBounds {
    private CombatBounds(){}
    public static boolean canAdvance(boolean insideAssignedArea,double pointDistance,double actorDistance,double leash){
        return insideAssignedArea||pointDistance<=leash||actorDistance>leash&&pointDistance<actorDistance-.025;
    }
}
