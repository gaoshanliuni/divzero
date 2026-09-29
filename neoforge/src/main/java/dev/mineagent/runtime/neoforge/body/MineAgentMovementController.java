package dev.mineagent.runtime.neoforge.body;

import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import dev.mineagent.runtime.core.task.SurfacePathfinder.Action;
import java.util.*;

/** Executes typed navigation steps on the existing ServerPlayer, once per native body tick. */
public final class MineAgentMovementController {
    private final NativeNavigationIntent intent=new NativeNavigationIntent();
    private long commandRevision;private int lastTick=Integer.MIN_VALUE;
    private int executedSteps,openedDoors,openedGates,crouchingSteps,climbingSteps,swimmingSteps;
    private boolean manualSneak;private final Set<Double> traversedFloors=new LinkedHashSet<>();
    public void recordOpened(boolean gate){if(gate)openedGates++;else openedDoors++;}
    public Map<String,Object> evidence(){var out=new LinkedHashMap<String,Object>();out.put("steps",executedSteps);out.put("crouchingSteps",crouchingSteps);out.put("openedDoors",openedDoors);out.put("openedGates",openedGates);out.put("observedGroundHeights",List.copyOf(traversedFloors));out.put("climbingSteps",climbingSteps);out.put("swimmingSteps",swimmingSteps);out.put("search",intent.evidence());return out;}
    public boolean manualSneak(){return manualSneak;}
    public void setSneaking(MineAgentPlayer p,boolean value){manualSneak=value;p.applySneaking(value||NativeSurfaceNavigation.requiresSneaking(p,p.position()));}
    public double arrivalTolerance(){return intent.tolerance();}
    public long commandRevision(){return commandRevision;}
    public String outcome(){return intent.status();}
    public int executedSteps(){return executedSteps;}
    public boolean stopIfCurrent(long command){if(command!=commandRevision)return false;intent.stop("CANCELLED");return true;}
    public void movePreciselyTo(Vec3 target){start(target,.2);}
    public void moveTo(Vec3 target){start(target,.8);}
    private void start(Vec3 target,double tolerance){intent.start(target,tolerance);commandRevision++;executedSteps=openedDoors=openedGates=crouchingSteps=climbingSteps=swimmingSteps=0;traversedFloors.clear();}
    /** Tracking a moving entity does not replace the command or discard a still-useful route. */
    public boolean updateTarget(long command,Vec3 target){if(command!=commandRevision)return false;intent.update(target);return true;}
    public void stop(){manualSneak=false;commandRevision++;intent.stop("CANCELLED");}
    public Optional<Vec3> target(){return intent.target();}
    public String backendName(){return "builtin-surface-actions";}
    public void tick(MineAgentPlayer p){
        int tick=p.level().getServer().getTickCount();if(lastTick==tick)return;lastTick=tick;
        p.applySneaking(p.canAct()&&(manualSneak||NativeSurfaceNavigation.requiresSneaking(p,p.position())));
        if(!p.canAct()||intent.target().isEmpty())return;
        if(p.onGround()&&traversedFloors.size()<128)traversedFloors.add(Math.rint(p.getY()*16)/16);
        var step=intent.tick(p);if(step==null)return;var waypoint=NativeTraversalEvaluator.point(step.to());var offset=waypoint.subtract(p.position());
        if(!NativeSurfaceNavigation.openOnPath(p,waypoint)){intent.stop("INTERACTION_BLOCKED");return;}
        boolean swim=step.action()==Action.SWIM||step.action()==Action.ENTER_WATER;
        boolean climb=step.action()==Action.CLIMB;
        p.applySneaking(!swim&&!climb&&(manualSneak||NativeSurfaceNavigation.requiresSneaking(p,waypoint)));
        p.lookAlongPath(waypoint.add(0,p.getEyeHeight(),0));
        if((step.action()==Action.JUMP||step.action()==Action.LEAVE_WATER)&&offset.y>.65&&p.onGround())p.jumpFromGround();
        double speed=Math.max(.01,p.getAttributeValue(Attributes.MOVEMENT_SPEED))*(p.isCrouching()?.3:1.1);
        var horizontal=new Vec3(offset.x,0,offset.z);if(horizontal.lengthSqr()>.001){p.move(MoverType.SELF,horizontal.normalize().scale(Math.min(speed,horizontal.length())));executedSteps++;if(p.isCrouching())crouchingSteps++;}
        if(climb&&p.onClimbable()){p.setDeltaMovement(p.getDeltaMovement().x,Math.max(-.15,Math.min(.2,offset.y)),p.getDeltaMovement().z);p.move(MoverType.SELF,new Vec3(0,Math.max(-.1,Math.min(.12,offset.y)),0));climbingSteps++;}
        if(swim&&p.isInWater()){p.setDeltaMovement(p.getDeltaMovement().x,0,p.getDeltaMovement().z);p.move(MoverType.SELF,new Vec3(0,Math.max(-.1,Math.min(.1,offset.y)),0));swimmingSteps++;}
        if(step.action()==Action.LEAVE_WATER&&p.isInWater()&&offset.y>0)p.setDeltaMovement(p.getDeltaMovement().x,.25,p.getDeltaMovement().z);
    }
}