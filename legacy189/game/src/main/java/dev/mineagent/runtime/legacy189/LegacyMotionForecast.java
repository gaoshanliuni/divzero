package dev.mineagent.runtime.legacy189;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.*;
import java.util.*;

/** Short, bounded observation forecast. Never reads inputs or changes the observed player. */
public final class LegacyMotionForecast {
    private EntityPlayerMP subject;
    private Vec3 previous, velocity=new Vec3(0,0,0), acceleration=new Vec3(0,0,0);
    private int observed=-1, samples, reversalAt=-10000;
    private int impulseUntil=-1;
    private float previousVital=Float.NaN;
    private final List<Vec3> points=new ArrayList<Vec3>();
    public Vec3 landing;
    public int landingTicks=-1, reversals, airborneFrames;
    public boolean descending, complete,settlingImpulse;
    public void reset(){subject=null;previous=null;observed=-1;samples=reversals=airborneFrames=0;reversalAt=-10000;impulseUntil=-1;previousVital=Float.NaN;settlingImpulse=false;velocity=acceleration=new Vec3(0,0,0);points.clear();landing=null;landingTicks=-1;}
    public void observe(EntityPlayerMP target,int tick){
        if(subject==target&&observed==tick)return;
        Vec3 at=target.getPositionVector();int elapsed=tick-observed;
        if(subject!=target||elapsed<1||elapsed>3||previous==null||previous.squareDistanceTo(at)>16){
            subject=target;samples=0;velocity=acceleration=new Vec3(0,0,0);previousVital=Float.NaN;impulseUntil=-1;
        }else{
            Vec3 raw=at.subtract(previous),delta=new Vec3(raw.xCoord/elapsed,raw.yCoord/elapsed,raw.zCoord/elapsed);
            double speed=Math.hypot(delta.xCoord,delta.zCoord);
            if(speed>.8)delta=new Vec3(delta.xCoord*.8/speed,delta.yCoord,delta.zCoord*.8/speed);
            if(samples>0){
                double dot=delta.xCoord*velocity.xCoord+delta.zCoord*velocity.zCoord;
                if(dot<-.003&&tick-reversalAt>2){reversals++;reversalAt=tick;}
                acceleration=new Vec3(clamp((delta.xCoord-velocity.xCoord)/elapsed,-.06,.06),0,clamp((delta.zCoord-velocity.zCoord)/elapsed,-.06,.06));
            }
            velocity=new Vec3(delta.xCoord,clamp(delta.yCoord,-3.9,1.2),delta.zCoord);samples++;
        }
        float vital=target.getHealth()+target.getAbsorptionAmount();
        // A real client's velocity packet takes effect after server damage. Its old
        // descending samples must not certify a landing before the impulse is observed.
        if(!Float.isNaN(previousVital)&&vital<previousVital-.001)impulseUntil=tick+2;
        previousVital=vital;settlingImpulse=tick<=impulseUntil;
        previous=at;observed=tick;landing=null;landingTicks=-1;points.clear();points.add(at);complete=true;
        boolean grounded=target.onGround, ballistic=!target.isInWater()&&!target.isOnLadder()&&!target.capabilities.isFlying;
        descending=!grounded&&ballistic&&velocity.yCoord<-.07;if(!grounded)airborneFrames++;
        AxisAlignedBB box=target.getEntityBoundingBox();
        double vx=velocity.xCoord,vz=velocity.zCoord,vy=grounded?0:(velocity.yCoord-.08)*.98;
        for(int t=1;t<=24;t++){
            // Acceleration is useful through a strafe reversal, but cannot grow indefinitely.
            double fade=Math.pow(.5,t);vx+=acceleration.xCoord*fade;vz+=acceleration.zCoord*fade;
            double speed=Math.hypot(vx,vz),limit=Math.max(.3,Math.hypot(velocity.xCoord,velocity.zCoord)+.08);
            if(speed>limit){vx*=limit/speed;vz*=limit/speed;}
            double dx=vx,dz=vz,dy=ballistic?(grounded?-.08:vy):0;
            AxisAlignedBB query=box.addCoord(dx,dy,dz);
            if(!target.worldObj.isAreaLoaded(new BlockPos(query.minX,query.minY,query.minZ),new BlockPos(query.maxX,query.maxY,query.maxZ))){complete=false;break;}
            List<AxisAlignedBB> obstacles=target.worldObj.getCollidingBoundingBoxes(target,query);
            double wantedY=dy;
            for(AxisAlignedBB obstacle:obstacles)dy=obstacle.calculateYOffset(box,dy);box=box.offset(0,dy,0);
            for(AxisAlignedBB obstacle:obstacles)dx=obstacle.calculateXOffset(box,dx);box=box.offset(dx,0,0);
            for(AxisAlignedBB obstacle:obstacles)dz=obstacle.calculateZOffset(box,dz);box=box.offset(0,0,dz);
            Vec3 next=new Vec3((box.minX+box.maxX)/2,box.minY,(box.minZ+box.maxZ)/2);points.add(next);
            boolean support=wantedY<0&&Math.abs(dy-wantedY)>1e-7;
            if(!settlingImpulse&&!target.onGround&&support&&landing==null){landing=next;landingTicks=t;}
            grounded=support;if(dx!=vx)vx=0;if(dz!=vz)vz=0;
            vy=grounded?0:((dy!=wantedY?0:vy)-.08)*.98;
        }
    }
    public Vec3 at(double ticks){
        if(points.isEmpty())return previous==null?new Vec3(0,0,0):previous;
        double t=clamp(ticks,0,points.size()-1);int i=(int)t;Vec3 a=points.get(i),b=points.get(Math.min(i+1,points.size()-1));
        return a.addVector((b.xCoord-a.xCoord)*(t-i),(b.yCoord-a.yCoord)*(t-i),(b.zCoord-a.zCoord)*(t-i));
    }
    public Vec3 velocity(){return velocity;}
    public double uncertainty(int ticks){return Math.min(1.1,.08+Math.max(0,ticks)*.035+(observed-reversalAt<8?.25:0)+(samples<2?.2:0)+(settlingImpulse?.6:0));}
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
}
