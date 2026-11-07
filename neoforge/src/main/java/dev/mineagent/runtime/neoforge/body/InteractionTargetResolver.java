package dev.mineagent.runtime.neoforge.body;

import dev.mineagent.runtime.core.task.SurfacePathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.util.*;

/** Interaction targets remain separate from the feet position; all candidates use the route evaluator. */
public final class InteractionTargetResolver {
    public enum Kind { BLOCK, PLACE, FISH, RANGED }
    public record Query(NativeTraversalEvaluator evaluator,SurfacePathfinder.Search search,List<Vec3> candidates){}
    public static Vec3 topHit(ServerPlayer player,BlockPos block){double top=player.level().getBlockState(block).getShape(player.level(),block).toAabbs().stream().mapToDouble(b->b.maxY).max().orElse(1);return new Vec3(block.getX()+.5,block.getY()+top-.001,block.getZ()+.5);}
    public static boolean lineOfSight(ServerPlayer p,Vec3 eye,Vec3 target,BlockPos acceptedBlock,boolean fluids){
        var hit=p.level().clip(new ClipContext(eye,target,ClipContext.Block.COLLIDER,fluids?ClipContext.Fluid.ANY:ClipContext.Fluid.NONE,p));return hit.getType()==HitResult.Type.MISS||acceptedBlock!=null&&hit.getBlockPos().equals(acceptedBlock);
    }
    public static Query block(ServerPlayer p,BlockPos block,Kind kind){
        return block(p,block,kind,null);
    }
    public static Query block(ServerPlayer p,BlockPos block,Kind kind,net.minecraft.world.level.block.state.BlockState desired){
        var evaluator=new NativeTraversalEvaluator(p);var start=evaluator.closest(p.position());var nodes=new LinkedHashSet<SurfacePathfinder.Node>();var offsets=new ArrayList<int[]>();
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)if(x!=0||z!=0)offsets.add(new int[]{x,z});offsets.sort(Comparator.comparingDouble(d->p.position().distanceToSqr(Vec3.atBottomCenterOf(block.offset(d[0],0,d[1])))));
        for(var d:offsets){if(nodes.size()>=24)break;for(var n:evaluator.positions(block.getX()+d[0],block.getZ()+d[1],block.getY())){
            if(evaluator.water(n)||evaluator.climb(n))continue;var at=NativeTraversalEvaluator.point(n);
            if(kind==Kind.PLACE){
                var pose=p.getDimensions(net.minecraft.world.entity.Pose.CROUCHING);
                // Placement uses the navigator's 0.2-block precision. A single ray
                // from its exact centre can be clear while the actual body is occluded.
                boolean usable=true;
                placementWindow: for(double x:new double[]{-.2,0,.2})for(double z:new double[]{-.2,0,.2}){
                    var feet=at.add(x,0,z);
                    if(pose.makeBoundingBox(feet).deflate(.0001).intersects(new AABB(block))){usable=false;break placementWindow;}
                    var eye=feet.add(0,pose.eyeHeight(),0);
                    if(NativeTerrainRecovery.placementHitFrom(p,block,eye,desired)==null){
                        double rise=NativeTerrainRecovery.placementJumpRise(p,feet);
                        if(rise<=0||NativeTerrainRecovery.placementHitFrom(p,block,eye.add(0,rise,0),desired)==null){usable=false;break placementWindow;}
                    }
                }
                if(usable)nodes.add(n);
                continue;
            }
            var eye=at.add(0,p.getEyeHeight(),0);double distance=eye.distanceTo(Vec3.atCenterOf(block));
            if(distance>(kind==Kind.FISH?5:Math.min(4.25,p.blockInteractionRange()))||kind==Kind.FISH&&distance<2)continue;
            if(lineOfSight(p,eye,Vec3.atCenterOf(block),block,kind==Kind.FISH))nodes.add(n);
        }}
        if(start==null||nodes.isEmpty())return new Query(evaluator,null,List.of());
        return new Query(evaluator,new SurfacePathfinder.Search(start,nodes::contains,n->0,evaluator),nodes.stream().map(NativeTraversalEvaluator::point).toList());
    }
    private InteractionTargetResolver(){}
    /** Reachable delivery positions, with room for navigation's settling tolerance. */
    public static Query handoff(ServerPlayer player,ServerPlayer recipient){
        var evaluator=new NativeTraversalEvaluator(player);var start=evaluator.closest(player.position());
        var nodes=new LinkedHashSet<SurfacePathfinder.Node>();var base=recipient.blockPosition();
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)for(var node:evaluator.positions(base.getX()+x,base.getZ()+z,recipient.getY())){
            var at=NativeTraversalEvaluator.point(node);
            if(evaluator.water(node)||evaluator.climb(node)||at.distanceToSqr(recipient.position())>1.45*1.45||Math.abs(at.y-recipient.getY())>1)continue;
            if(player.getDimensions(player.getPose()).makeBoundingBox(at).intersects(recipient.getBoundingBox()))continue;
            if(lineOfSight(player,at.add(0,player.getEyeHeight(),0),recipient.position().add(0,.5,0),null,false))nodes.add(node);
        }
        if(start==null||nodes.isEmpty())return new Query(evaluator,null,List.of());
        return new Query(evaluator,new SurfacePathfinder.Search(start,nodes::contains,n->0,evaluator),nodes.stream().map(NativeTraversalEvaluator::point).toList());
    }
    /** Candidate feet positions must allow a real attack; the target's occupied block is not a waypoint. */
    public static Query melee(ServerPlayer p,net.minecraft.world.entity.LivingEntity target){
        var evaluator=new NativeTraversalEvaluator(p);var nodes=new LinkedHashSet<SurfacePathfinder.Node>();
        double reach=p.getAttackRangeWith(p.getMainHandItem()).effectiveMaxRange(p);int radius=(int)Math.ceil(Math.min(8,reach+target.getBbWidth()));var base=target.blockPosition();
        for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++)for(var n:evaluator.positions(base.getX()+x,base.getZ()+z,target.getY())){
            var at=NativeTraversalEvaluator.point(n);var box=p.getDimensions(p.getPose()).makeBoundingBox(at);if(box.intersects(target.getBoundingBox()))continue;
            var eye=at.add(0,p.getEyeHeight(),0);if(eye.distanceTo(target.getEyePosition())>reach+target.getBbWidth())continue;
            if(NativeTargetGeometry.pointFrom(p,eye,target.getHitbox()).filter(hit->hit.distanceTo(eye)<=reach).isPresent()||NativeTargetGeometry.pointFrom(p,at.add(0,p.getDimensions(net.minecraft.world.entity.Pose.CROUCHING).eyeHeight(),0),target.getHitbox()).isPresent())nodes.add(n);
        }
        var points=nodes.stream().map(NativeTraversalEvaluator::point).sorted(Comparator.comparingDouble(at->at.distanceToSqr(p.position()))).toList();
        return new Query(evaluator,null,points);
    }
}
