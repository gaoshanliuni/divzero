package dev.mineagent.runtime.legacy189;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityExpBottle;
import net.minecraft.entity.item.EntityEnderPearl;
import net.minecraft.entity.projectile.*;
import net.minecraft.util.*;
import java.util.*;

/** Native box/reach/look observations and air/water arrow integration. No incoming hit is cancelled. */
public final class LegacyThreatSensor {
    private final List<Flight> flights=new ArrayList<Flight>();
    public final LegacyMotionForecast motion=new LegacyMotionForecast();
    private int lastSwing=-10000;
    private EntityPlayerMP opponent;
    private NativeAgent actor;
    private int now;
    public int projectileThreatFrames,meleeThreatFrames;
    public void reset(){flights.clear();motion.reset();opponent=null;lastSwing=-10000;projectileThreatFrames=meleeThreatFrames=0;}
    public void observe(NativeAgent actor,EntityPlayerMP target,int tick){
        this.actor=actor;this.opponent=target;now=tick;motion.observe(target,tick);
        if(target.isSwingInProgress)lastSwing=tick;
        flights.clear();List<Entity> projectiles=actor.worldObj.getEntitiesWithinAABB(Entity.class,actor.getEntityBoundingBox().expand(32,16,32));
        projectiles.sort(Comparator.comparingDouble(actor::getDistanceSqToEntity));
        for(Entity projectile:projectiles){
            if(flights.size()>=64)break;if(projectile.isDead)continue;
            double gravity,damage,airDrag=.99,waterDrag=.8,ax=0,ay=0,az=0;
            if(projectile instanceof EntityArrow){EntityArrow arrow=(EntityArrow)projectile;net.minecraft.nbt.NBTTagCompound state=new net.minecraft.nbt.NBTTagCompound();arrow.writeToNBT(state);if(state.getBoolean("inGround")||arrow.shootingEntity==actor&&arrow.ticksExisted<5)continue;gravity=.05;waterDrag=.6;damage=Math.max(1,Math.ceil(Math.sqrt(arrow.motionX*arrow.motionX+arrow.motionY*arrow.motionY+arrow.motionZ*arrow.motionZ)*arrow.getDamage()));}
            else if(projectile instanceof EntityThrowable){EntityThrowable thrown=(EntityThrowable)projectile;if(thrown.getThrower()==actor||projectile instanceof EntityExpBottle)continue;gravity=projectile instanceof EntityPotion?.05:.03;damage=projectile instanceof EntityPotion?4:projectile instanceof EntityEnderPearl?3:1;}
            else if(projectile instanceof EntityLargeFireball||projectile instanceof EntitySmallFireball){EntityFireball fireball=(EntityFireball)projectile;if(fireball.shootingEntity==actor)continue;gravity=0;airDrag=.95;ax=fireball.accelerationX;ay=fireball.accelerationY;az=fireball.accelerationZ;damage=6;}
            else continue;
            double vx=projectile.motionX,vy=projectile.motionY,vz=projectile.motionZ;if(vx*vx+vy*vy+vz*vz<.00001)continue;
            Vec3 at=projectile.getPositionVector();List<Vec3> points=new ArrayList<Vec3>();points.add(at);
            for(int t=0;t<12;t++){
                Vec3 next=at.addVector(vx,vy,vz);if(!actor.worldObj.isBlockLoaded(new BlockPos(next)))break;
                MovingObjectPosition block=actor.worldObj.rayTraceBlocks(at,next,false,true,false);
                if(block!=null){points.add(block.hitVec);break;}points.add(next);
                boolean water=actor.worldObj.getBlockState(new BlockPos(next)).getBlock().getMaterial()==net.minecraft.block.material.Material.water;
                double drag=water?waterDrag:airDrag;vx=(vx+ax)*drag;vy=(vy+ay)*drag-gravity;vz=(vz+az)*drag;at=next;
            }
            if(points.size()>1)flights.add(new Flight(points,damage));
        }
        if(projectileRisk(actor.getPositionVector(),6)>0)projectileThreatFrames++;
        if(activeMelee()&&meleeRisk(actor.getPositionVector(),2)>0)meleeThreatFrames++;
    }
    public boolean activeMelee(){return now-lastSwing<=12||actor.hurtTime>0;}
    public double risk(Vec3 destination,int arrival){return projectileRisk(destination,arrival)+meleeRisk(destination,arrival)+landingRisk(destination,arrival);}
    public boolean dropThreat(){return motion.descending&&opponent.posY>actor.posY+.5&&meleeRisk(actor.getPositionVector(),8)+landingRisk(actor.getPositionVector(),8)>.5;}
    public double landingRisk(Vec3 destination,int arrival){
        if(!motion.descending||motion.landing==null||motion.landingTicks>12||Math.abs(motion.landing.yCoord-actor.posY)>2.5)return 0;
        double fraction=Math.min(1,motion.landingTicks/(double)Math.max(1,arrival));Vec3 from=actor.getPositionVector();
        double x=from.xCoord+(destination.xCoord-from.xCoord)*fraction,z=from.zCoord+(destination.zCoord-from.zCoord)*fraction;
        return Math.max(0,3.2+motion.uncertainty(motion.landingTicks)-Math.hypot(motion.landing.xCoord-x,motion.landing.zCoord-z))*ModernCombat.baseDamage(opponent)*.6;
    }
    public double projectileRisk(Vec3 destination,int arrival){
        double risk=0;Vec3 origin=actor.getPositionVector();
        for(Flight flight:flights)for(int t=1;t<flight.points.size();t++){
            double fraction=Math.min(1,t/(double)Math.max(1,arrival));
            Vec3 point=origin.addVector((destination.xCoord-origin.xCoord)*fraction,(destination.yCoord-origin.yCoord)*fraction,(destination.zCoord-origin.zCoord)*fraction);
            AxisAlignedBB box=actor.getEntityBoundingBox().offset(point.xCoord-origin.xCoord,point.yCoord-origin.yCoord,point.zCoord-origin.zCoord).expand(.3,.3,.3);
            Vec3 from=flight.points.get(t-1),to=flight.points.get(t);
            if(box.isVecInside(from)||box.calculateIntercept(from,to)!=null){risk+=flight.damage*(1+(12-t)/24d);break;}
        }
        return risk;
    }
    public double meleeRisk(Vec3 destination,int horizon){
        double risk=0;Vec3 origin=actor.getPositionVector(),look=opponent.getLookVec();
        double reach=opponent.capabilities.isCreativeMode?4.5:3,damage=ModernCombat.baseDamage(opponent);
        for(int t=1;t<=Math.max(2,Math.min(8,horizon));t++){
            double fraction=Math.min(1,t/(double)Math.max(1,horizon));
            AxisAlignedBB box=actor.getEntityBoundingBox().offset((destination.xCoord-origin.xCoord)*fraction,(destination.yCoord-origin.yCoord)*fraction,(destination.zCoord-origin.zCoord)*fraction);
            Vec3 eye=motion.at(t).addVector(0,opponent.getEyeHeight(),0);
            Vec3 nearest=new Vec3(clamp(eye.xCoord,box.minX+.001,box.maxX-.001),clamp(eye.yCoord,box.minY+.001,box.maxY-.001),clamp(eye.zCoord,box.minZ+.001,box.maxZ-.001));
            if(eye.squareDistanceTo(nearest)>reach*reach||actor.worldObj.rayTraceBlocks(eye,nearest,false,true,false)!=null)continue;
            Vec3 end=eye.addVector(look.xCoord*reach,look.yCoord*reach,look.zCoord*reach);
            boolean aimed=box.expand(.1,.1,.1).isVecInside(eye)||box.expand(.1,.1,.1).calculateIntercept(eye,end)!=null;
            double probability=activeMelee()?(aimed?1:.25):(aimed?.08:.02);
            if(motion.descending&&opponent.posY>actor.posY+.5)probability=Math.max(probability,aimed?1.5:.65);
            if(actor.hurtResistantTime-t>10)probability*=.25;
            risk=Math.max(risk,damage*probability);
        }
        return risk;
    }
    public Vec3 opponentVelocity(){return motion.velocity();}
    private static double clamp(double value,double low,double high){return Math.max(low,Math.min(high,value));}
    private static final class Flight{final List<Vec3> points;final double damage;Flight(List<Vec3> points,double damage){this.points=points;this.damage=damage;}}
}
