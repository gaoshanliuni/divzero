package dev.mineagent.runtime.neoforge.body;

import dev.mineagent.runtime.core.task.SurfacePathfinder.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;

/** One/two empty cells are ordinary jump edges when the actual arc and landing are usable. */
public final class NativeGapTraversal {
    public static Vec3 takeoff(PathStep step) {
        var a = NativeTraversalEvaluator.point(step.from());
        return a.add(NativeTraversalEvaluator.point(step.to()).subtract(a).normalize().scale(.22));
    }
    static PathStep edge(ServerPlayer p, NativeTraversalEvaluator terrain, Node from, Node to) {
        int dx = Math.abs(from.x() - to.x()), dz = Math.abs(from.z() - to.z()), span = dx + dz;
        if (dx != 0 && dz != 0 || span < 2 || span > 3 || Math.abs(from.y() - to.y()) > .1
                || span==3&&!NativeTravelPolicy.canSprint(p) || p.isUsingItem() || p.isPassenger() || terrain.water(from) || terrain.water(to) || terrain.climb(from) || terrain.climb(to)
                || !(p instanceof dev.mineagent.runtime.neoforge.mixin.CombatJumpAccess access)) return null;
        var a = NativeTraversalEvaluator.point(from); var b = NativeTraversalEvaluator.point(to);
        if (!NativeTravelPolicy.supported(p, a, .05) || !NativeTravelPolicy.supported(p, b, .05)) return null;
        int sx = Integer.signum(to.x() - from.x()), sz = Integer.signum(to.z() - from.z());
        for (int i = 1; i < span; i++)
            if (terrain.positions(from.x() + sx * i, from.z() + sz * i, from.y()).stream().anyMatch(n -> Math.abs(n.y() - from.y()) < .1)) return null;
        double velocity = access.divzero$jumpPower(), gravity = p.getAttributeValue(Attributes.GRAVITY), height = 0;
        if (!Double.isFinite(velocity) || !Double.isFinite(gravity) || velocity <= 0 || gravity <= 0) return null;
        var heights = new ArrayList<Double>(); heights.add(0.0);
        for (int tick = 0; tick < 30; tick++) {
            height += velocity; heights.add(Math.max(0, height)); velocity = (velocity - gravity) * .98;
            if (height <= 0) break;
        }
        if (height > 0 || heights.size() < 3) return null;
        var step = new PathStep(from, to, Action.GAP_JUMP, Posture.STANDING, span + .35);
        var launch = takeoff(step); double distance = launch.distanceTo(b);
        if (distance / (heights.size() - 1) > NativeTravelPolicy.sprintSpeed(p, a) * .95) return null;
        // Read all loaded collision cells along the arc, including headroom, takeoff and landing.
        for (int i = 0; i < heights.size(); i++) {
            double t = i / (double)(heights.size() - 1);
            var feet = launch.lerp(b, t).add(0, heights.get(i), 0);
            if (!terrain.clear(feet, Pose.STANDING, false)) return null;
            if (i > 0) {
                double previous = (i - 1) / (double)(heights.size() - 1);
                var middle = launch.lerp(b, (previous + t) / 2).add(0, (heights.get(i - 1) + heights.get(i)) / 2, 0);
                if (!terrain.clear(middle, Pose.STANDING, false)) return null;
            }
        }
        return step;
    }
    private NativeGapTraversal() {}
}
