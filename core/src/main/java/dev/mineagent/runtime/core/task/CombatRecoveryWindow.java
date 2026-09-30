package dev.mineagent.runtime.core.task;

/** Retreat for an actual recovery opportunity, without waiting forever for unavailable healing. */
public final class CombatRecoveryWindow {
    private int progressAt=-1;
    private float previousHealth=Float.NaN;
    private boolean previousOpportunity;
    public boolean shouldRecover(int tick,float health,float maximum,boolean opportunity,int lastDamageTick){
        if(health>=maximum*.3f){progressAt=-1;previousHealth=health;previousOpportunity=opportunity;return false;}
        if(progressAt<0||health>previousHealth+.01f||opportunity&&!previousOpportunity)progressAt=tick;
        previousHealth=health;previousOpportunity=opportunity;
        // New damage still buys a short escape. Continued retreat then needs observed healing or usable supplies.
        return tick-lastDamageTick<60||opportunity&&tick-progressAt<160;
    }
}
