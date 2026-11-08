package dev.mineagent.runtime.neoforge.body;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/** Shared terrain-dependent posture. Careful navigation is not an instruction to hold Shift. */
public final class NativeTravelPolicy {
    public static boolean canSprint(ServerPlayer p) {
        return (p.getFoodData().getFoodLevel() > 6 || p.hasInfiniteMaterials()) && !p.isInWater()
                && !p.onClimbable() && !p.isUsingItem() && !p.isPassenger();
    }
    public static boolean supported(ServerPlayer p, Vec3 at, double margin) {
        double radius = p.getBbWidth() / 2 + margin;
        for (double x : new double[]{-radius, radius}) for (double z : new double[]{-radius, radius}) {
            var pos = BlockPos.containing(at.x + x, at.y - .05, at.z + z);
            if (!p.level().hasChunkAt(pos) || !p.level().getWorldBorder().isWithinBounds(pos)) return false;
            var state = p.level().getBlockState(pos);
            if (!state.getFluidState().isEmpty()) return false;
            boolean found = false;
            for (var box : state.getCollisionShape(p.level(), pos).toAabbs()) {
                var b = box.move(pos);
                if (at.x + x >= b.minX && at.x + x <= b.maxX && at.z + z >= b.minZ && at.z + z <= b.maxZ
                        && Math.abs(at.y - b.maxY) < .12) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }
    public static boolean sneak(ServerPlayer p, Vec3 next, String action) {
        if (NativeSurfaceNavigation.requiresSneaking(p, next)) return true;
        if (action.equals("GAP_JUMP") || action.equals("DROP") || action.equals("SWIM") || action.equals("CLIMB")) return false;
        if (!p.onGround()) return false;
        var delta = next.subtract(p.position());
        var ahead = p.position().add(delta.scale(Math.min(1, .8 / Math.max(.001, delta.length()))));
        return !supported(p, p.position(), .22) || !supported(p, ahead, .22);
    }
    public static boolean sprint(ServerPlayer p, Vec3 next, Vec3 destination, String action) {
        if (!canSprint(p) || action.equals("CROUCH")) return false;
        if (action.equals("GAP_JUMP")) return true;
        if (!action.equals("WALK") || destination == null || p.position().subtract(destination).horizontalDistanceSqr() < 2.25) return false;
        return !sneak(p, next, action) && p.level().noCollision(p, p.getDimensions(Pose.STANDING).makeBoundingBox(next));
    }
    /** Same ground travel rate used by the AI controller, with ordinary native sprinting. */
    public static double sprintSpeed(ServerPlayer p, Vec3 start) {
        double speed = p.getAttributeValue(Attributes.MOVEMENT_SPEED) * (p.isSprinting() || !canSprint(p) ? 1 : 1.3);
        double friction = Math.max(.1, p.level().getBlockState(BlockPos.containing(start).below()).getBlock().getFriction());
        return .98 * speed * .216 / (friction * friction * friction) / Math.max(.05, 1 - friction * .91);
    }
    private NativeTravelPolicy() {}
}
