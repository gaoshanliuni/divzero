package dev.mineagent.runtime.legacy189;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.*;
import net.minecraft.util.*;
import dev.mineagent.runtime.legacy189.navigation.SurfacePathfinder.Node;

/** Main/secondary hotbar tactics. Tools remain available to native terrain mining. */
public final class LegacyLoadoutController {
    private int lastPearl=-1000,lastProbe=-1000;
    public int switches,pearlsThrown;
    public Vec3 lastPearlOrigin,lastPearlLanding;
    private static boolean melee(ItemStack stack){return stack!=null&&(stack.getItem() instanceof ItemSword||stack.getItem() instanceof ItemAxe);}
    private static boolean bow(ItemStack stack){return stack!=null&&stack.getItem() instanceof ItemBow;}
    public boolean tick(NativeAgent actor,EntityPlayerMP target,ArenaNavigator nav,int tick){
        if(nav.recovering())return false;
        ItemStack primary=actor.inventory.getStackInSlot(0),secondary=actor.inventory.getStackInSlot(3);
        double distance=actor.getDistanceToEntity(target);boolean ammo=actor.inventory.hasItem(Items.arrow);int slot=0;
        if(secondary!=null){
            if(melee(secondary)&&(primary==null||!melee(primary)&&(distance<4.5||!bow(primary)||!ammo)))slot=3;
            else if(bow(secondary)&&ammo&&(primary==null||!melee(primary)||distance>6.5))slot=3;
        }
        if(actor.inventory.currentItem!=slot){actor.clearItemInUse();actor.inventory.currentItem=slot;switches++;}
        if(secondary==null||secondary.getItem()!=Items.ender_pearl||secondary.stackSize<=0||tick-lastPearl<50||tick-lastProbe<12||distance<10||distance>28||actor.getHealth()<=8||actor.isUsingItem()||!actor.onGround)return false;
        lastProbe=tick;Throw plan=pearlPlan(actor,target);if(plan==null)return false;
        actor.clearItemInUse();actor.inventory.currentItem=3;actor.rotationYaw=plan.yaw;actor.rotationYawHead=plan.yaw;actor.rotationPitch=plan.pitch;
        actor.moveForward=actor.moveStrafing=0;actor.setSprinting(false);
        int count=secondary.stackSize;ItemStack after=secondary.useItemRightClick(actor.worldObj,actor);
        actor.inventory.setInventorySlotContents(3,after==null||after.stackSize<=0?null:after);actor.swingItem();
        if(after==null||after.stackSize<count){pearlsThrown++;lastPearl=tick;lastPearlOrigin=actor.getPositionVector();lastPearlLanding=plan.landing;}
        return true;
    }
    private Throw pearlPlan(NativeAgent actor,EntityPlayerMP target){
        LegacyTraversal terrain=new LegacyTraversal(actor);double original=actor.getDistanceToEntity(target),best=original-4;
        float heading=(float)Math.toDegrees(Math.atan2(target.posZ-actor.posZ,target.posX-actor.posX))-90;Throw chosen=null;
        long deadline=System.nanoTime()+3_000_000L;
        for(int turn:new int[]{0,-12,12,-24,24})for(int pitch:new int[]{10,20,0,30,-10,40,-20,50,-30,60,-40}){
            if(System.nanoTime()>deadline)return chosen;
            double yaw=Math.toRadians(heading+turn),angle=Math.toRadians(pitch);
            Vec3 at=actor.getPositionEyes(1).addVector(-Math.cos(yaw)*.16,-.1,-Math.sin(yaw)*.16);
            double vx=-Math.sin(yaw)*Math.cos(angle)*1.5,vz=Math.cos(yaw)*Math.cos(angle)*1.5,vy=-Math.sin(angle)*1.5;
            for(int t=1;t<=36;t++){
                Vec3 next=at.addVector(vx,vy,vz);if(!actor.worldObj.isBlockLoaded(new BlockPos(next)))break;
                // Entity impacts are not a dependable safe landing spot.
                if(target.getEntityBoundingBox().expand(.4,.4,.4).calculateIntercept(at,next)!=null)break;
                MovingObjectPosition hit=actor.worldObj.rayTraceBlocks(at,next,false,true,false);
                if(hit!=null){
                    // Vanilla 1.8.9 teleports to the projectile's position BEFORE impact.
                    Node floor=terrain.closest(at);double remaining=at.distanceTo(target.getPositionVector());
                    if(floor!=null&&floor.y()<=at.yCoord+.01&&at.yCoord-floor.y()<2.5&&remaining>=2.5&&remaining+t*.015<best&&terrain.clear(at,false)){
                        boolean safe=true;
                        for(double[] d:new double[][]{{.4,0},{-.4,0},{0,.4},{0,-.4}}){Vec3 test=at.addVector(d[0],0,d[1]);Node near=terrain.closest(test);if(near==null||Math.abs(near.y()-floor.y())>.1||!terrain.clear(new Vec3(test.xCoord,floor.y(),test.zCoord),false)){safe=false;break;}}
                        if(safe){best=remaining+t*.015;chosen=new Throw(heading+turn,pitch,at);}
                    }
                    break;
                }
                at=next;vx*=.99;vy=vy*.99-.03;vz*=.99;
            }
        }
        return chosen;
    }
    private static final class Throw {final float yaw,pitch;final Vec3 landing;Throw(float yaw,float pitch,Vec3 landing){this.yaw=yaw;this.pitch=pitch;this.landing=landing;}}
}
