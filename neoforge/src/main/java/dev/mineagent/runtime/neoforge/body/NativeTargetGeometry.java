package dev.mineagent.runtime.neoforge.body;

import net.minecraft.world.entity.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.util.*;

/** Visible points of the real hitbox, including low openings. No attack through a solid collision shape. */
public final class NativeTargetGeometry {
    public static Optional<Vec3> point(LivingEntity viewer, Entity target) {
        return pointFrom(viewer,viewer.getEyePosition(),target instanceof LivingEntity living?living.getHitbox():target.getBoundingBox());
    }
    public static Optional<Vec3> pointFrom(LivingEntity viewer,Vec3 eye,AABB box) {
        double inset=Math.min(.03,Math.min(box.getXsize(),Math.min(box.getYsize(),box.getZsize()))/4),x=Math.clamp(eye.x,box.minX+inset,box.maxX-inset),z=Math.clamp(eye.z,box.minZ+inset,box.maxZ-inset);
        double[] heights={Math.clamp(eye.y,box.minY+inset,box.maxY-inset),box.minY+Math.min(.08,box.getYsize()*.15),box.minY+Math.min(.4,box.getYsize()*.25),(box.minY+box.maxY)/2,box.maxY-inset};
        var candidates=new ArrayList<Vec3>();for(double y:heights){candidates.add(new Vec3(x,y,z));candidates.add(new Vec3((box.minX+box.maxX)/2,y,(box.minZ+box.maxZ)/2));}
        candidates.sort(Comparator.comparingDouble(eye::distanceToSqr));
        for(var at:candidates){var hit=viewer.level().clip(new ClipContext(eye,at,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,viewer));if(hit.getType()==HitResult.Type.MISS)return Optional.of(at);}
        return Optional.empty();
    }
    public static Optional<Vec3> attackPoint(net.minecraft.world.entity.player.Player viewer,Entity target){
        double range=viewer.getAttackRangeWith(viewer.getMainHandItem()).effectiveMaxRange(viewer);
        return point(viewer,target).filter(at->at.distanceToSqr(viewer.getEyePosition())<=range*range+.001);
    }
    public static Optional<Vec3> crouchedPoint(net.minecraft.world.entity.player.Player viewer,LivingEntity target) {
        var dimensions=viewer.getDimensions(Pose.CROUCHING);
        if(!viewer.level().noCollision(viewer,dimensions.makeBoundingBox(viewer.position())))return Optional.empty();
        return pointFrom(viewer,viewer.position().add(0,dimensions.eyeHeight(),0),target.getHitbox());
    }
    public static boolean canObserve(net.minecraft.world.entity.player.Player viewer,LivingEntity target) {
        return point(viewer,target).isPresent()||crouchedPoint(viewer,target).isPresent();
    }
    private NativeTargetGeometry(){}
}
