package dev.mineagent.runtime.legacy189;

import com.google.gson.JsonObject;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Items;
import net.minecraft.item.*;
import net.minecraft.util.*;

/** Finite retreat, reposition, native bow draw/release and approach. Never invents an arrow entity. */
public final class LegacyRangedController {
    private final LegacyThreatSensor threats=new LegacyThreatSensor();
    private int drawAt=-1,escapeUntil=-1,lastRelease=-10000,shots,ammoSpent,draws,approachTicks,escapeTicks,dodgeTicks,failedReleases;
    private double escapeDistance;
    private int lastEscape=-10000;
    private String phase="APPROACH";
    public void reset(){drawAt=escapeUntil=-1;lastRelease=lastEscape=-10000;shots=ammoSpent=draws=approachTicks=escapeTicks=dodgeTicks=failedReleases=0;threats.reset();phase="APPROACH";}
    public JsonObject evidence(){JsonObject r=new JsonObject();r.addProperty("phase",phase);r.addProperty("shots",shots);r.addProperty("arrowsConsumed",ammoSpent);r.addProperty("draws",draws);r.addProperty("approachTicks",approachTicks);r.addProperty("escapeTicks",escapeTicks);r.addProperty("dodgeTicks",dodgeTicks);r.addProperty("failedReleases",failedReleases);return r;}
    private static int ammunition(EntityPlayerMP p){int n=0;for(ItemStack stack:p.inventory.mainInventory)if(stack!=null&&stack.getItem()==Items.arrow)n+=stack.stackSize;return n;}
    private void cancel(NativeAgent actor){actor.clearItemInUse();drawAt=-1;}
    public void tick(NativeAgent actor,EntityPlayerMP target,ArenaNavigator navigation,int tick){
        threats.observe(actor,target,tick);ItemStack held=actor.getHeldItem();if(held==null||!(held.getItem() instanceof ItemBow)){cancel(actor);return;}
        double dx=target.posX-actor.posX,dz=target.posZ-actor.posZ,distance=Math.hypot(dx,dz);
        if(ammunition(actor)==0&&!actor.capabilities.isCreativeMode){cancel(actor);phase="NO_AMMO_APPROACH";navigation.move(actor,target,tick,false);if(ModernCombat.reachable(actor,target)){actor.swingItem();actor.attackTargetEntityWithCurrentItem(target);}return;}
        boolean visible=actor.canEntityBeSeen(target);
        if(!visible||distance>13){cancel(actor);phase="APPROACH";approachTicks++;navigation.move(actor,target,tick,false);if(!navigation.recovering()&&actor.moveForward>.7f)actor.setSprinting(true);return;}
        if(navigation.recovering()){cancel(actor);navigation.move(actor,target,tick,false);phase="TERRAIN_APPROACH";return;}
        if(escapeUntil<tick&&distance<3.6&&drawAt<0&&tick-lastRelease>12&&tick-lastEscape>=40){escapeUntil=tick+12;escapeDistance=distance;lastEscape=tick;}
        if(escapeUntil>=tick&&distance<6){
            double nx=-dx/Math.max(.01,distance),nz=-dz/Math.max(.01,distance);
            if(LegacyMeleeController.safeMotion(actor,nx*1.3,nz*1.3,false)&&!(escapeUntil-tick<5&&distance<escapeDistance+.35)){
                cancel(actor);actor.rotationYaw=(float)Math.toDegrees(Math.atan2(nz,nx))-90;actor.rotationYawHead=actor.rotationYaw;
                actor.moveForward=1;actor.moveStrafing=0;actor.setSprinting(true);phase="ESCAPE_REPOSITION";escapeTicks++;return;
            }
            escapeUntil=-1;
        }
        escapeUntil=-1;
        aim(actor,target,threats.motion,distance);
        actor.setSprinting(false);actor.moveForward=distance>9?.65f:distance<3?-.35f:0;actor.moveStrafing=0;
        double standing=threats.risk(actor.getPositionVector(),6),best=standing;float strafe=0;
        actor.decisionRisk=standing;
        for(float side:new float[]{-1,1}){
            double yaw=Math.toRadians(actor.rotationYaw),x=side*Math.cos(yaw),z=side*Math.sin(yaw);
            if(!LegacyMeleeController.safeMotion(actor,x*.8,z*.8,false))continue;
            double score=threats.risk(actor.getPositionVector().addVector(x*.8,0,z*.8),6);
            double[] features={actor.getHealth()/Math.max(1,actor.getMaxHealth()),Math.min(2,distance/16),Math.hypot(actor.motionX,actor.motionZ)/.4,0,1,0,Math.min(2,score/80),.125,x/8,z/8,0,1,5d/8,actor.onGround?0:1,ModernCombat.baseDamage(actor)/10,0};
            score+=actor.policy().cost(features)*.2;
            if(score<best-.05){best=score;strafe=side;}
        }
        actor.moveStrafing=strafe;if(strafe!=0)dodgeTicks++;
        if(strafe!=0&&threats.projectileRisk(actor.getPositionVector(),6)>0){cancel(actor);actor.moveForward=0;phase="PROJECTILE_DODGE";return;}
        if(!LegacyMeleeController.safeMotion(actor,-Math.sin(Math.toRadians(actor.rotationYaw))*actor.moveForward*.8,Math.cos(Math.toRadians(actor.rotationYaw))*actor.moveForward*.8,false))actor.moveForward=0;
        if(drawAt>=0&&!actor.isUsingItem())drawAt=-1;
        if(drawAt<0){
            if(tick-lastRelease<4)return;
            ItemStack use=held.useItemRightClick(actor.worldObj,actor);actor.inventory.setInventorySlotContents(actor.inventory.currentItem,use);
            if(!actor.isUsingItem()){phase="DRAW_REJECTED";return;}drawAt=tick;draws++;
        }
        if(actor.getItemInUse()!=actor.getHeldItem()){cancel(actor);phase="HELD_ITEM_CHANGED";return;}
        phase="DRAW";
        int heldTicks=tick-drawAt,required=distance<4?12:20;
        if(heldTicks>=required){
            int before=ammunition(actor),arrows=arrowCount(actor);
            // The clientless player holds this same real stack for observed server ticks.
            // Use the vanilla release method (including Forge ArrowLoose), not a custom projectile.
            held.onPlayerStoppedUsing(actor.worldObj,actor,held.getMaxItemUseDuration()-heldTicks);
            actor.clearItemInUse();drawAt=-1;lastRelease=tick;
            int used=before-ammunition(actor),created=arrowCount(actor)-arrows;
            if(created>0){shots+=created;ammoSpent+=Math.max(0,used);phase="FIRED";}else{failedReleases++;phase="RELEASE_REJECTED";}
        }
    }
    private static int arrowCount(NativeAgent actor){int n=0;for(EntityArrow arrow:actor.worldObj.getEntitiesWithinAABB(EntityArrow.class,actor.getEntityBoundingBox().expand(4,4,4)))if(!arrow.isDead&&arrow.shootingEntity==actor)n++;return n;}
    private static void aim(NativeAgent actor,EntityPlayerMP target,LegacyMotionForecast motion,double distance){
        double speed=distance<4?1.56:3,flight=Math.min(18,distance/speed);Vec3 predicted=motion.at(flight);
        for(int i=0;i<3;i++){
            double horizontal=Math.hypot(predicted.xCoord-actor.posX,predicted.zCoord-actor.posZ);
            flight=Math.min(20,Math.log(Math.max(.05,1-horizontal*.01/speed))/Math.log(.99));predicted=motion.at(flight);
        }
        double drop=5*(flight-(1-Math.pow(.99,flight))/.01);
        double dx=predicted.xCoord-actor.posX,dz=predicted.zCoord-actor.posZ;
        double dy=predicted.yCoord+target.getEyeHeight()*.75-actor.posY-actor.getEyeHeight()+drop;
        actor.rotationYaw=(float)Math.toDegrees(Math.atan2(dz,dx))-90;actor.rotationYawHead=actor.rotationYaw;actor.rotationPitch=(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz)));
    }
}
