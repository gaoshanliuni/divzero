package dev.mineagent.runtime.neoforge.body;

import dev.mineagent.runtime.agent.navigation.GroundPathfinder;
import dev.mineagent.runtime.agent.navigation.GridPos;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class MineAgentMovementController {
    private static final int MAX_EXPANDED_NODES = 8_192;
    private static final int REPLAN_INTERVAL_TICKS = 20;

    private Vec3 target;
    private Vec3 plannedTarget;
    private Vec3 lastProgressPosition;
    private List<Vec3> route = List.of();
    private int routeIndex;
    private int ticksUntilReplan;
    private int stuckTicks;
    private int failedPlans;
    private long commandRevision;
    private String outcome="IDLE";
    private int executedSteps,openedDoors,openedGates;private final java.util.Set<Double> traversedFloors=new java.util.LinkedHashSet<>();
    public void recordOpened(boolean gate){if(gate)openedGates++;else openedDoors++;}
    public java.util.Map<String,Object> evidence(){return java.util.Map.of("steps",executedSteps,"openedDoors",openedDoors,"openedGates",openedGates,"observedGroundHeights",java.util.List.copyOf(traversedFloors));}
    private double arrivalDistanceSqr=.64;
    public double arrivalTolerance(){return Math.sqrt(arrivalDistanceSqr);}
    public void movePreciselyTo(Vec3 target){moveTo(target);arrivalDistanceSqr=.04;}
    public long commandRevision(){return commandRevision;}
    public String outcome(){return outcome;}
    public int executedSteps(){return executedSteps;}
    public boolean stopIfCurrent(long command){if(command!=commandRevision)return false;finish("CANCELLED");return true;}

    public void moveTo(Vec3 target) {
        if (target == null || !Double.isFinite(target.x) || !Double.isFinite(target.y)
                || !Double.isFinite(target.z)) {
            throw new IllegalArgumentException("invalid navigation target");
        }
        this.target = target;
        arrivalDistanceSqr=.64;
        commandRevision++;outcome="MOVING";executedSteps=0;openedDoors=openedGates=0;traversedFloors.clear();route=List.of();routeIndex=0;failedPlans=0;stuckTicks=0;lastProgressPosition=null;
        this.ticksUntilReplan = 0;
    }

    public void stop() {
        commandRevision++;finish("CANCELLED");
    }
    private void finish(String status){
        outcome=status;
        target = null;
        plannedTarget = null;
        route = List.of();
        routeIndex = 0;
        stuckTicks = 0;
        failedPlans = 0;
    }

    public Optional<Vec3> target() {
        return Optional.ofNullable(target);
    }

    public String backendName() {
        return baritonePresent() ? "builtin-fallback(baritone-incompatible-server-player)" : "builtin";
    }

    public void tick(MineAgentPlayer player) {
        if (target == null || !player.canAct()) {
            return;
        }
        Vec3 offset = target.subtract(player.position());
        if (offset.horizontalDistanceSqr() < arrivalDistanceSqr && Math.abs(offset.y) < .26) {
            finish("ARRIVED");
            return;
        }
        if(player.onGround()&&traversedFloors.size()<128)traversedFloors.add(Math.rint(player.getY()*16)/16);
        updateProgress(player);
        ticksUntilReplan--;
        if ((route.isEmpty() || routeIndex >= route.size() || ticksUntilReplan <= 0
                || plannedTarget == null || plannedTarget.distanceToSqr(target) > 4.0 || stuckTicks >= 20)
                && (player.onGround() || player.isInWater())) {
            plan(player);
        }
        if (route.isEmpty() || routeIndex >= route.size()) {
            return;
        }
        Vec3 waypoint = route.get(routeIndex);
        Vec3 waypointCenter = waypoint;
        Vec3 waypointOffset = waypointCenter.subtract(player.position());
        if (waypointOffset.horizontalDistanceSqr() < (routeIndex==route.size()-1?Math.min(.16,arrivalDistanceSqr):.16) && Math.abs(waypointOffset.y) < .26) {
            routeIndex++;
            if (routeIndex >= route.size()) {
                return;
            }
            waypoint = route.get(routeIndex);
            waypointCenter = waypoint;
            waypointOffset = waypointCenter.subtract(player.position());
        }
        if(!NativeSurfaceNavigation.openOnPath(player,waypointCenter)){finish("INTERACTION_BLOCKED");return;}
        player.lookAt(EntityAnchorArgument.Anchor.EYES, waypointCenter.add(0,player.getEyeHeight(),0));
        Vec3 horizontal = new Vec3(waypointOffset.x, 0, waypointOffset.z);
        if (waypointOffset.y > 0.65 && player.onGround()) {
            player.jumpFromGround();
        }
        if (horizontal.lengthSqr() > 0.001) {
            Vec3 step = horizontal.normalize().scale(player.isSprinting() ? 0.16 : 0.11);
            player.move(MoverType.SELF, step);
            executedSteps++;
        }
    }

    private void plan(MineAgentPlayer player) {
        ticksUntilReplan = REPLAN_INTERVAL_TICKS;
        stuckTicks = 0;
        plannedTarget = target;
        BlockPos start = player.blockPosition();
        BlockPos goal = BlockPos.containing(target);
        int distance = Math.abs(start.getX() - goal.getX()) + Math.abs(start.getY() - goal.getY())
                + Math.abs(start.getZ() - goal.getZ());
        if (distance > 128) {
            Vec3 direction = target.subtract(player.position()).normalize().scale(128);
            goal = BlockPos.containing(player.position().add(direction));
        }
        var path=NativeSurfaceNavigation.find(player,Vec3.atBottomCenterOf(goal),MAX_EXPANDED_NODES);
        if(path.isEmpty()){route=List.of();routeIndex=0;if(++failedPlans>=3)finish("UNREACHABLE");return;}
        failedPlans=0;route=path;routeIndex=route.size()==1&&arrivalDistanceSqr<.16?0:Math.min(1,route.size());
        if(goal.getX()==(int)Math.floor(target.x)&&goal.getZ()==(int)Math.floor(target.z))target=new Vec3(target.x,route.getLast().y,target.z);
    }

    private void updateProgress(MineAgentPlayer player) {
        Vec3 current = player.position();
        if (lastProgressPosition == null || current.distanceToSqr(lastProgressPosition) >= 0.01) {
            lastProgressPosition = current;
            stuckTicks = 0;
        } else {
            stuckTicks++;
        }
    }

    private static boolean walkable(net.minecraft.server.level.ServerLevel level, BlockPos foot) {
        if(!bodyClear(level,foot))return false;
        BlockPos support = foot.below();
        return !level.getBlockState(support).getCollisionShape(level, support, CollisionContext.empty()).isEmpty()
                || !level.getFluidState(foot).isEmpty();
    }

    private static boolean bodyClear(net.minecraft.server.level.ServerLevel level, BlockPos foot) {
        if (!level.getChunkSource().hasChunk(foot.getX()>>4,foot.getZ()>>4)||!level.getWorldBorder().isWithinBounds(foot) || foot.getY() < level.getMinY()
                || foot.getY() + 1 > level.getMaxY()) {
            return false;
        }
        var emptyContext = CollisionContext.empty();
        boolean feetClear = level.getBlockState(foot).getCollisionShape(level, foot, emptyContext).isEmpty();
        BlockPos head = foot.above();
        boolean headClear = level.getBlockState(head).getCollisionShape(level, head, emptyContext).isEmpty();
        return feetClear && headClear;
    }

    private static GridPos grid(BlockPos position) {
        return new GridPos(position.getX(), position.getY(), position.getZ());
    }

    private static BlockPos block(GridPos position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private static boolean baritonePresent() {
        try {
            Class.forName("baritone.api.BaritoneAPI", false, MineAgentMovementController.class.getClassLoader());
            return true;
        } catch (LinkageError | ClassNotFoundException unavailable) {
            return false;
        }
    }
}
