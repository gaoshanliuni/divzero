package dev.mineagent.runtime.core.task;

/** Policy only. Each game adapter supplies damage from its own native rules and collision checks. */
public final class TraversalSafety {
    public static boolean affordableDrop(double height,double damage,double health,double absorption){
        return Double.isFinite(height)&&Double.isFinite(damage)&&Double.isFinite(health)&&Double.isFinite(absorption)
                &&height<=24&&damage>=0&&health>0&&absorption>=0
                &&(damage==0||damage<=3&&damage<=health*.25&&health+absorption-damage>=4);
    }
    /** Forward + strafe with the closest exact +/-45 degree yaw; no velocity or attribute output. */
    public record Steering(float yaw,float forward,float strafe){}
    public static Steering diagonal(double dx,double dz,float currentYaw){
        if(!Double.isFinite(dx)||!Double.isFinite(dz)||!Float.isFinite(currentYaw)||Math.hypot(dx,dz)<1e-6)throw new IllegalArgumentException("SPRINT_DIRECTION");
        float heading=(float)Math.toDegrees(Math.atan2(dz,dx))-90;
        int side=Math.abs(wrap(heading-45-currentYaw))<Math.abs(wrap(heading+45-currentYaw))?-1:1;
        return new Steering(heading+side*45,1,side);
    }
    private static float wrap(float value){return (value%360+540)%360-180;}
    private TraversalSafety(){}
}
