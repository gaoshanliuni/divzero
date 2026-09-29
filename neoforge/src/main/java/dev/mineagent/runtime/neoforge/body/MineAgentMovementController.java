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
    private long pendingJumpRevision=-1;private UUID pendingJumpOwner;
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
    public boolean stopIfCurrent(long command){if(command!=commandRevision)return false;pendingJumpRevision=-1;intent.stop("CANCELLED");return true;}
    public void movePreciselyTo(Vec3 target){start(target,.2);}
    public boolean followCheckedRoute(MineAgentPlayer player,List<dev.mineagent.runtime.core.task.SurfacePathfinder.PathStep> route){if(!intent.followCheckedRoute(player,route))return false;commandRevision++;return true;}
    public void tacticalJump(MineAgentPlayer player,UUID owner){pendingJumpRevision=commandRevision;pendingJumpOwner=owner;}
    public void moveTo(Vec3 target){start(target,.8);}
    private void start(Vec3 target,double tolerance){intent.start(target,tolerance);commandRevision++;executedSteps=openedDoors=openedGates=crouchingSteps=climbingSteps=swimmingSteps=0;traversedFloors.clear();}
    /** Tracking a moving entity does not replace the command or discard a still-useful route. */
    public boolean updateTarget(long command,Vec3 target){if(command!=commandRevision)return false;intent.update(target);return true;}
    public void stop(){manualSneak=false;pendingJumpRevision=-1;commandRevision++;intent.stop("CANCELLED");}
    public Optional<Vec3> target(){return intent.target();}
    public String backendName(){return "builtin-surface-actions";}
    public void tick(MineAgentPlayer p){
        int tick=p.level().getServer().getTickCount();if(lastTick==tick)return;lastTick=tick;
        p.applySneaking(p.canAct()&&(manualSneak||NativeSurfaceNavigation.requiresSneaking(p,p.position())));
        if(!p.canAct()||intent.target().isEmpty())return;
        if(p.onGround()&&traversedFloors.size()<128)traversedFloors.add(Math.rint(p.getY()*16)/16);
        var step=intent.tick(p);if(step==null)return;var waypoint=intent.waypoint(step);var offset=waypoint.subtract(p.position());
        if(pendingJumpRevision!=commandRevision||pendingJumpOwner==null||!p.controls().owns(pendingJumpOwner,dev.mineagent.runtime.api.agent.BodyDomain.MOVEMENT)){pendingJumpRevision=-1;pendingJumpOwner=null;}
        else if(p.onGround()&&p.isSprinting()){intent.tacticalJump(p);p.jumpFromGround();pendingJumpRevision=-1;pendingJumpOwner=null;}
        if(!NativeSurfaceNavigation.openOnPath(p,waypoint)){intent.stop("INTERACTION_BLOCKED");return;}
        boolean swim=step.action()==Action.SWIM||step.action()==Action.ENTER_WATER;
        boolean climb=step.action()==Action.CLIMB;
        p.setSwimming(swim&&p.isInWater());
        p.applySneaking(!swim&&!climb&&(manualSneak||NativeSurfaceNavigation.requiresSneaking(p,waypoint)));
        var travelHeading=new Vec3(offset.x,0,offset.z);if(travelHeading.lengthSqr()>.001)p.lookAlongPath(p.getEyePosition().add(travelHeading.normalize().scale(4)));
        if((step.action()==Action.JUMP||step.action()==Action.LEAVE_WATER)&&offset.y>.65&&p.onGround())p.jumpFromGround();
        double friction=Math.max(.1,p.level().getBlockState(p.blockPosition().below()).getBlock().getFriction());
        var useEffects=p.getUseItem().getOrDefault(net.minecraft.core.component.DataComponents.USE_EFFECTS,net.minecraft.world.item.component.UseEffects.DEFAULT);
        if(p.isUsingItem()&&!useEffects.canSprint())p.setSprinting(false);
        double speed=.98*Math.max(0,p.getAttributeValue(Attributes.MOVEMENT_SPEED))*(p.isInWater()?1+p.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY):.216/(friction*friction*friction)/Math.max(.05,1-friction*.91))*p.navigationSpeedFactor();
        if(p.isUsingItem()&&!p.isPassenger())speed*=useEffects.speedMultiplier();
        if(p.isCrouching())speed*=p.getAttributeValue(Attributes.SNEAKING_SPEED);
        var horizontal=new Vec3(offset.x,0,offset.z);if(horizontal.lengthSqr()>.001&&speed>0){p.move(MoverType.SELF,horizontal.normalize().scale(Math.min(speed,horizontal.length())));executedSteps++;if(p.isCrouching())crouchingSteps++;}
        // Native travel integrates vertical velocity once; do not additionally move by the same input.
        if(climb&&p.onClimbable()){p.setDeltaMovement(p.getDeltaMovement().x,offset.y>0?Math.min(.2,Math.max(.12,offset.y)):Math.max(-.15,offset.y),p.getDeltaMovement().z);climbingSteps++;}
        if(swim&&p.isInWater()){p.setDeltaMovement(p.getDeltaMovement().x,Math.max(-.1,Math.min(.1,offset.y)),p.getDeltaMovement().z);swimmingSteps++;}
        if(step.action()==Action.LEAVE_WATER&&p.isInWater()&&offset.y>0)p.setDeltaMovement(p.getDeltaMovement().x,.25,p.getDeltaMovement().z);
    }
}
