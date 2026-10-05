package dev.mineagent.runtime.legacy189;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.Vec3;

/** Projectile avoidance remains available while travelling to a distant opponent. */
public final class LegacyProjectileGuard {
    private final LegacyThreatSensor sensor=new LegacyThreatSensor();
    public int threatFrames,dodgeFrames;
    public boolean evade(NativeAgent actor,EntityPlayerMP target,int tick){
        sensor.observe(actor,target,tick);Vec3 at=actor.getPositionVector();double standing=sensor.projectileRisk(at,6);
        if(standing<=0)return false;threatFrames++;double best=standing;float forward=0,strafe=0;double yaw=Math.toRadians(actor.rotationYaw);
        for(float[] input:new float[][]{{0,1},{0,-1},{-.7f,.7f},{-.7f,-.7f},{-1,0}}){
            double dx=input[1]*Math.cos(yaw)-input[0]*Math.sin(yaw),dz=input[0]*Math.cos(yaw)+input[1]*Math.sin(yaw);
            if(!LegacyMeleeController.safeMotion(actor,dx*1.2,dz*1.2,!actor.onGround))continue;
            double risk=sensor.projectileRisk(at.addVector(dx*1.2,0,dz*1.2),6);
            double[] features={actor.getHealth()/Math.max(1,actor.getMaxHealth()),Math.min(2,actor.getDistanceToEntity(target)/16),Math.hypot(actor.motionX,actor.motionZ)/.4,0,1,0,Math.min(2,risk/80),.125,dx/8,dz/8,0,0,5d/8,actor.onGround?0:1,ModernCombat.baseDamage(actor)/10,0};
            double score=risk+actor.policy().cost(features)*.1;
            if(score<best-.05){best=score;forward=input[0];strafe=input[1];}
        }
        if(forward==0&&strafe==0)return false;
        actor.clearItemInUse();actor.moveForward=forward;actor.moveStrafing=strafe;actor.setSprinting(false);actor.decisionRisk=standing;dodgeFrames++;return true;
    }
}
