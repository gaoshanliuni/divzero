package dev.mineagent.runtime.legacy189.navigation;

/** Hit-confirmed 1.8.9 footwork. These are movement phases, never weapon cooldowns. */
public final class ComboRhythm {
    private int lastHit=-10000, releaseUntil=-10000, chain, maximum;
    private boolean backTap;
    public void reset(){lastHit=releaseUntil=-10000;chain=maximum=0;backTap=false;}
    public void interrupted(){lastHit=releaseUntil=-10000;chain=0;backTap=false;}
    public void hit(int tick,double distance,double radialSpeed){
        chain=tick-lastHit<=24?chain+1:1;maximum=Math.max(maximum,chain);
        lastHit=tick;backTap=distance<2.45||distance<2.75&&radialSpeed<-.04;
        releaseUntil=tick+(backTap?3:2);
    }
    public boolean resetting(int tick){return tick<releaseUntil;}
    public boolean backTap(){return backTap;}
    public int lastHit(){return lastHit;}
    public int maximum(){return maximum;}
    public int chain(int tick){return tick-lastHit>24?0:chain;}
    public float forward(int tick,double distance,double radialSpeed){
        if(resetting(tick))return backTap?-.85f:0;
        if(distance<2.15)return -.65f;
        if(distance>2.65||tick-lastHit<24&&distance>2.3||radialSpeed>.04&&distance>2.35)return 1;
        return .15f;
    }
    public boolean sprint(int tick,float forward,boolean airborneTap){return !resetting(tick)&&forward>.7f&&!airborneTap;}
    public String phase(int tick){return resetting(tick)?backTap?"S_TAP":"W_TAP":tick-lastHit<24?"COMBO_PRESSURE":"APPROACH";}
}
