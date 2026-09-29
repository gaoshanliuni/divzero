package dev.mineagent.runtime.core.task;

/** Direction commitment prevents per-tick A/D oscillation. Risk is supplied by native collision checks. */
public final class CombatFootwork {
    private int side,changedAt=-10000,lastJump=-10000;
    public int side(){return side;}
    public int choose(int tick,double left,double right){
        if(!Double.isFinite(left)&&!Double.isFinite(right))return 0;
        int best=left<=right?1:-1;
        double current=side==1?left:right,other=side==1?right:left;
        if(side!=0&&Double.isFinite(current)){
            if(tick-changedAt<12&&other+8>=current)return side;
            if(tick-changedAt>=12&&Double.isFinite(other)&&other<=current+1.5)best=-side;
        }
        if(best!=side){side=best;changedAt=tick;}
        return side;
    }
    public boolean jumpReady(int tick,boolean grounded,boolean hitConfirmed,boolean clearArc){return grounded&&hitConfirmed&&clearArc&&tick-lastJump>=28;}
    public void jumped(int tick){lastJump=tick;}
    public void reset(){side=0;changedAt=-10000;}
}
