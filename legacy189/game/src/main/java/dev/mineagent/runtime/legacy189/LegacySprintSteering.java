package dev.mineagent.runtime.legacy189;

/** Native 45-degree sprint inputs; never edits velocity, speed attributes or jump impulse. */
public final class LegacySprintSteering {
    private LegacySprintSteering(){}
    public static boolean apply(NativeAgent actor){
        if(!actor.sprint45Requested||!actor.onGround||!actor.isSprinting()||actor.isUsingItem()||actor.isSneaking()||actor.isInWater()||actor.isOnLadder()||actor.isCollidedHorizontally)return false;
        double f=actor.moveForward,s=actor.moveStrafing,length=Math.hypot(f,s);if(length<.95)return false;
        double yaw=Math.toRadians(actor.rotationYaw),dx=(s*Math.cos(yaw)-f*Math.sin(yaw))/length,dz=(f*Math.cos(yaw)+s*Math.sin(yaw))/length;
        if(!LegacyMeleeController.safeMotion(actor,dx*.7,dz*.7,false))return false;
        float heading=(float)Math.toDegrees(Math.atan2(dz,dx))-90;
        float left=net.minecraft.util.MathHelper.wrapAngleTo180_float(heading-45-actor.rotationYaw),right=net.minecraft.util.MathHelper.wrapAngleTo180_float(heading+45-actor.rotationYaw);
        int side=Math.abs(left)<Math.abs(right)?-1:1;
        actor.rotationYaw=heading+side*45;actor.rotationYawHead=actor.rotationYaw;
        actor.moveForward=1;actor.moveStrafing=side;actor.sprint45Ticks++;return true;
    }
}
