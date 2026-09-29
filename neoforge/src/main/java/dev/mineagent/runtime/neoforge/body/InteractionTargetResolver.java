package dev.mineagent.runtime.neoforge.body;

import dev.mineagent.runtime.core.task.SurfacePathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.util.*;

/** Interaction targets remain separate from the feet position; all candidates use the route evaluator. */
public final class InteractionTargetResolver {
    public enum Kind { BLOCK, FISH, RANGED }
    public record Query(NativeTraversalEvaluator evaluator,SurfacePathfinder.Search search,List<Vec3> candidates){}
    public static Vec3 topHit(ServerPlayer player,BlockPos block){double top=player.level().getBlockState(block).getShape(player.level(),block).toAabbs().stream().mapToDouble(b->b.maxY).max().orElse(1);return new Vec3(block.getX()+.5,block.getY()+top-.001,block.getZ()+.5);}
    public static boolean lineOfSight(ServerPlayer p,Vec3 eye,Vec3 target,BlockPos acceptedBlock,boolean fluids){
        var hit=p.level().clip(new ClipContext(eye,target,ClipContext.Block.COLLIDER,fluids?ClipContext.Fluid.ANY:ClipContext.Fluid.NONE,p));return hit.getType()==HitResult.Type.MISS||acceptedBlock!=null&&hit.getBlockPos().equals(acceptedBlock);
    }
    public static Query block(ServerPlayer p,BlockPos block,Kind kind){
        var evaluator=new NativeTraversalEvaluator(p);var start=evaluator.closest(p.position());var nodes=new LinkedHashSet<SurfacePathfinder.Node>();var offsets=new ArrayList<int[]>();
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)if(x!=0||z!=0)offsets.add(new int[]{x,z});offsets.sort(Comparator.comparingDouble(d->p.position().distanceToSqr(Vec3.atBottomCenterOf(block.offset(d[0],0,d[1])))));
        for(var d:offsets){if(nodes.size()>=24)break;for(var n:evaluator.positions(block.getX()+d[0],block.getZ()+d[1],block.getY())){
            if(evaluator.water(n)||evaluator.climb(n))continue;var at=NativeTraversalEvaluator.point(n);var eye=at.add(0,p.getEyeHeight(),0);double distance=eye.distanceTo(Vec3.atCenterOf(block));
            if(distance>(kind==Kind.FISH?5:Math.min(4.25,p.blockInteractionRange()))||kind==Kind.FISH&&distance<2)continue;
            if(lineOfSight(p,eye,Vec3.atCenterOf(block),block,kind==Kind.FISH))nodes.add(n);
        }}
        if(start==null||nodes.isEmpty())return new Query(evaluator,null,List.of());
        return new Query(evaluator,new SurfacePathfinder.Search(start,nodes::contains,n->0,evaluator),nodes.stream().map(NativeTraversalEvaluator::point).toList());
    }
    private InteractionTargetResolver(){}
}
