package dev.mineagent.runtime.legacy189;

import dev.mineagent.runtime.legacy189.navigation.NavigationRetry;
import dev.mineagent.runtime.legacy189.navigation.SurfacePathfinder;
import dev.mineagent.runtime.legacy189.navigation.SurfacePathfinder.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.*;
import java.util.*;

/** PvP adapter of 26.1.2 NativeNavigationIntent and MineAgentMovementController.
 * Uses the generated original SurfacePathfinder/TerrainPathSearch algorithms. */
public final class ArenaNavigator {
    private final NavigationRetry retry=new NavigationRetry();
    private final LegacyTerrainRecovery recovery=new LegacyTerrainRecovery();
    private SurfacePathfinder.Search search;
    private LegacyTraversal evaluator;
    private List<PathStep> steps=Collections.emptyList();
    private Vec3 plannedTarget,lastPosition;
    private int index,stuck;
    public int plans,stuckRecoveries,woolBroken;
    public String reason="IDLE";
    public boolean recovering(){return recovery.active();}
    public int placed(){return recovery.placed;}
    public int consumed(){return recovery.consumed;}
    public void reset(NativeAgent actor){stop(actor);recovery.reset(actor);plans=stuckRecoveries=woolBroken=0;retry.reset(0);plannedTarget=lastPosition=null;}
    public void stop(NativeAgent actor){recovery.cancel();steps=Collections.emptyList();search=null;index=stuck=0;actor.stopActions();}
    private void recheck(int tick){steps=Collections.emptyList();search=null;index=0;retry.reset(tick);reason="TERRAIN_CHANGED_RECHECK";}
    public boolean move(NativeAgent actor,EntityPlayerMP target,int tick,boolean bow){
        double distance=actor.getDistanceToEntity(target);
        if(!recovery.active()&&(bow?distance<9&&actor.canEntityBeSeen(target):ModernCombat.reachable(actor,target))){steps=Collections.emptyList();search=null;stuck=0;return false;}
        actor.moveForward=actor.moveStrafing=0;actor.setSprinting(false);
        Vec3 goal=target.getPositionVector();PathStep step=null;
        if(!recovery.active())step=route(actor,target,goal,tick);
        if(recovery.active()){
            boolean[] changed={false};step=recovery.tick(tick,changed);woolBroken=recovery.broken;
            if(changed[0])recheck(tick);reason=recovery.state;
        }
        if(step==null)return true;
        Vec3 waypoint=LegacyTraversal.point(step.to()),offset=waypoint.subtract(actor.getPositionVector());
        LegacyTraversal check=new LegacyTraversal(actor);if(!check.openOnPath(waypoint)){reason="INTERACTION_BLOCKED";recheck(tick+5);return true;}
        // The old clientless body integrates vanilla travel once per tick. Convert the
        // same waypoint to native local movement inputs instead of moving it twice.
        double length=Math.hypot(offset.xCoord,offset.zCoord),yaw=Math.toRadians(actor.rotationYaw);
        if(length>.025){
            double amount=Math.min(1,length/.22);
            actor.moveForward=(float)((-offset.xCoord*Math.sin(yaw)+offset.zCoord*Math.cos(yaw))/length*amount);
            actor.moveStrafing=(float)((offset.xCoord*Math.cos(yaw)+offset.zCoord*Math.sin(yaw))/length*amount);
        }
        actor.setSneaking(step.posture()==Posture.CROUCHING);
        if((step.action()==Action.JUMP||step.action()==Action.LEAVE_WATER)&&offset.yCoord>.65&&actor.onGround)actor.requestJump();
        if(step.action()==Action.CLIMB&&actor.isOnLadder())actor.motionY=offset.yCoord>0?Math.min(.2,Math.max(.12,offset.yCoord)):Math.max(-.15,offset.yCoord);
        if((step.action()==Action.SWIM||step.action()==Action.ENTER_WATER)&&actor.isInWater())actor.motionY=Math.max(-.1,Math.min(.1,offset.yCoord));
        if(step.action()==Action.LEAVE_WATER&&actor.isInWater()&&offset.yCoord>0)actor.motionY=.25;
        return true;
    }
    private PathStep route(NativeAgent actor,EntityPlayerMP target,Vec3 goal,int tick){
        Vec3 position=actor.getPositionVector();
        if(lastPosition==null||lastPosition.squareDistanceTo(position)>=.01){lastPosition=position;stuck=0;}else if(index<steps.size())stuck++;
        boolean moved=plannedTarget!=null&&plannedTarget.squareDistanceTo(goal)>2.25;
        if(stuck>=30&&actor.onGround&&recovery.request(actor,target,tick)){
            stuckRecoveries++;search=null;steps=Collections.emptyList();index=stuck=0;reason="REPEATED_ROUTE_OBSTRUCTION";return null;
        }
        if(moved||stuck>=30){search=null;steps=Collections.emptyList();index=0;if(stuck>=30){reason="TEMPORARY_CONGESTION";retry.waitUntil(tick+10);}stuck=0;}
        while(index<steps.size()){
            Vec3 next=LegacyTraversal.point(steps.get(index).to());double dx=next.xCoord-actor.posX,dz=next.zCoord-actor.posZ;
            if(dx*dx+dz*dz<.04&&Math.abs(next.yCoord-actor.posY)<.26)index++;else break;
        }
        if(index>=steps.size()&&search==null&&retry.ready(tick)){
            evaluator=new LegacyTraversal(actor);Node start=evaluator.closest(position);Vec3 routeGoal=goal;
            boolean segment=goal.squareDistanceTo(position)>256;
            if(segment){Vec3 heading=goal.subtract(position).normalize();routeGoal=position.addVector(heading.xCoord*16,heading.yCoord*16,heading.zCoord*16);}
            Node end=evaluator.closest(routeGoal);plans++;plannedTarget=goal;
            if(segment&&end==null){for(int radius=1;radius<=3&&end==null;radius++)for(int dx=-radius;dx<=radius&&end==null;dx++)for(int dz=-radius;dz<=radius;dz++){
                Node candidate=evaluator.closest(routeGoal.addVector(dx,0,dz));if(candidate!=null&&LegacyTraversal.point(candidate).squareDistanceTo(position)>4){end=candidate;break;}
            }}
            if(start==null||end==null){reason=evaluator.encounteredUnloaded()?"WAITING_CHUNKS":"INVALID_TARGET";if(evaluator.encounteredUnloaded())retry.waitUntil(tick+20);else{retry.failed(tick);if(start!=null&&recovery.request(actor,target,tick,!evaluator.neighbors(start).isEmpty()))reason="APPROACH_BLOCKED_TARGET";}return null;}
            search=new SurfacePathfinder.Search(start,end,evaluator);
        }
        if(search!=null){
            evaluator.beginSlice();final long deadline=System.nanoTime()+3_000_000L;
            Result result=search.advance(96,()->System.nanoTime()<deadline);reason=result.status().name();
            if(result.status()==Status.FOUND){steps=result.steps();index=0;search=null;retry.succeeded(tick);}
            else if(result.status()!=Status.BUDGET_EXHAUSTED){
                search=null;
                if(evaluator.encounteredUnloaded()){retry.waitUntil(tick+20);return null;}
                retry.failed(tick);
                Node current=evaluator.closest(position);boolean pursue=current!=null&&!evaluator.neighbors(current).isEmpty();
                if(result.status()==Status.NO_PATH&&recovery.request(actor,target,tick,pursue))reason=pursue?"APPROACH_BLOCKED_TARGET":"NO_ORDINARY_PATH";
                return null;
            }else return null;
        }
        if(index>=steps.size())return null;PathStep step=steps.get(index);LegacyTraversal check=new LegacyTraversal(actor);
        if(!step.from().equals(step.to())&&check.transition(step.from(),step.to())==null||!check.clear(LegacyTraversal.point(step.to()),check.water(step.from())||check.water(step.to()))){
            steps=Collections.emptyList();index=0;reason=check.encounteredUnloaded()?"WAITING_CHUNKS":"ROUTE_CHANGED";retry.waitUntil(tick+5);return null;
        }
        return step;
    }
}
