package dev.mineagent.runtime.core.task;

/** Range hysteresis keeps sprint escape separate from charging and weak item melee. */
public final class RangedContactEscape {
    private boolean active;
    private int clearAt=-1,started=-1,fireUntil=-1;
    public boolean active(){return active;}
    public boolean update(int tick,double distance,boolean threatened,boolean ranged,boolean melee,boolean loadedShot){
        if(!ranged||melee){reset();return false;}
        if(!active&&tick<fireUntil)return false;
        if(!active&&(distance<4||threatened)&&!loadedShot){active=true;started=tick;}
        if(!active)return false;
        // A chase or wall cannot keep the archer in an infinite escape/charge-cancel loop.
        if(tick-started>=40){active=false;clearAt=-1;fireUntil=tick+35;return false;}
        if(distance>=8&&!threatened){if(clearAt<0)clearAt=tick;if(tick-clearAt>=4){reset();return false;}}
        else clearAt=-1;
        return true;
    }
    public void reset(){active=false;clearAt=started=fireUntil=-1;}
}
