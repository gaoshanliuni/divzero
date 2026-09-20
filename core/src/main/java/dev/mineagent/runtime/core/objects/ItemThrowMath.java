package dev.mineagent.runtime.core.objects;
/** Charge is measured on the server. Invalid speed never silently becomes a different throw. */
public final class ItemThrowMath {
    private ItemThrowMath(){}
    public static final int USE_DURATION=1200;
    public static int usedTicks(int remaining){if(remaining<0||remaining>USE_DURATION)throw new IllegalArgumentException("ITEM_USE_TIME");return USE_DURATION-remaining;}
    public static double charge(int used,int full){if(used<0||used>USE_DURATION||full<1||full>200)throw new IllegalArgumentException("ITEM_CHARGE_TIME");return Math.min(1.0,(double)used/full);}
    public static ObjectPhysics.Motion launch(double x,double y,double z,double speed,double lift){
        if(!Double.isFinite(speed)||speed<=0||speed>3||!Double.isFinite(lift)||lift< -1||lift>1)throw new IllegalArgumentException("ITEM_THROW_SPEED");
        double length=Math.sqrt(x*x+y*y+z*z);if(!Double.isFinite(length)||length<.99||length>1.01)throw new IllegalArgumentException("ITEM_THROW_DIRECTION");
        return ObjectPhysics.limit(new ObjectPhysics.Motion(x*speed,y*speed+lift,z*speed));
    }
}
