package dev.mineagent.runtime.neoforge.body;

import dev.mineagent.runtime.core.task.SurfacePathfinder.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import java.util.*;

/** One collision/pose/door/water/ladder evaluator for routing and multi-target reachability. */
public final class NativeTraversalEvaluator implements dev.mineagent.runtime.core.task.SurfacePathfinder.TraversalEvaluator {
    private final ServerPlayer player;private final Object level;private final Vec3 origin;private boolean unloaded;
    // Only a search slice owns these entries; no negative result survives a world tick.
    private final Map<Node,List<PathStep>> sliceCache=new HashMap<>();
    public NativeTraversalEvaluator(ServerPlayer player){this.player=player;level=player.level();origin=player.position();}
    public void beginSlice(){sliceCache.clear();}
    public boolean current(){return player.level()==level&&player.isAlive()&&!player.isRemoved();}
    @Override public boolean encounteredUnloaded(){return unloaded;}
    public static Vec3 point(Node n){return new Vec3(n.x()+.5,n.y(),n.z()+.5);}
    public boolean loaded(BlockPos p){if(!player.level().hasChunkAt(p)){unloaded=true;return false;}return !player.level().isOutsideBuildHeight(p)&&player.level().getWorldBorder().isWithinBounds(p);}
    public boolean water(Node n){var p=BlockPos.containing(point(n));return loaded(p)&&player.level().getFluidState(p).is(FluidTags.WATER);}
    public boolean climb(Node n){var p=BlockPos.containing(point(n));return loaded(p)&&player.level().getBlockState(p).is(BlockTags.CLIMBABLE);}
    public boolean clear(Vec3 at,Pose pose,boolean allowWater){
        var box=player.getDimensions(pose).makeBoundingBox(at).deflate(.0001);var world=player.level();
        for(var pos:BlockPos.betweenClosed((int)Math.floor(box.minX),(int)Math.floor(box.minY),(int)Math.floor(box.minZ),(int)Math.floor(box.maxX),(int)Math.floor(box.maxY),(int)Math.floor(box.maxZ))){
            if(!loaded(pos))return false;var state=world.getBlockState(pos);
            if(!state.getFluidState().isEmpty()&&(!allowWater||!state.getFluidState().is(FluidTags.WATER))||state.is(Blocks.SWEET_BERRY_BUSH)||state.is(Blocks.WITHER_ROSE)||state.is(Blocks.CAMPFIRE)||state.is(Blocks.SOUL_CAMPFIRE)||state.is(Blocks.FIRE)||state.is(Blocks.SOUL_FIRE)||state.is(Blocks.CACTUS)||state.is(Blocks.POWDER_SNOW))return false;
            if(NativeSurfaceNavigation.openable(player,pos,state))continue;
            for(var shape:state.getCollisionShape(world,pos,CollisionContext.of(player)).toAabbs())if(shape.move(pos).intersects(box))return false;
        }return true;
    }
    public List<Node> positions(int x,int z,double near){
        var nodes=new LinkedHashSet<Node>();var base=new BlockPos(x,(int)Math.floor(near),z);if(!loaded(base))return List.of();
        for(var at:NativeSurfaceNavigation.standingPositions(player,x,z,near))nodes.add(new Node(x,(int)Math.round(at.y*16),z));
        for(int y=(int)Math.floor(near)-1;y<=(int)Math.floor(near)+1;y++){
            var n=new Node(x,y*16,z);if((water(n)&&clear(point(n),Pose.SWIMMING,true))||(climb(n)&&clear(point(n),Pose.STANDING,true)))nodes.add(n);
        }return List.copyOf(nodes);
    }
    public Node closest(Vec3 target){return positions((int)Math.floor(target.x),(int)Math.floor(target.z),target.y).stream().min(Comparator.comparingDouble(n->Math.abs(n.y()-target.y))).orElse(null);}
    public PathStep transition(Node from,Node to){
        var a=point(from);var b=point(to);double rise=b.y-a.y;boolean wetA=water(from),wetB=water(to),ladder=climb(from)||climb(to);
        int horizontal=Math.abs(from.x()-to.x())+Math.abs(from.z()-to.z());if(horizontal>1||horizontal==0&&!ladder&&!wetA||rise>1.251||rise< -3)return null;
        Pose pose=wetA&&wetB?Pose.SWIMMING:Pose.STANDING;boolean wet=wetA||wetB;
        if(!clear(b,pose,wet)){if(!wet&&clear(b,Pose.CROUCHING,false))pose=Pose.CROUCHING;else return null;}
        int samples=Math.max(1,(int)Math.ceil(a.distanceTo(b)*8));for(int i=0;i<=samples;i++){double t=i/(double)samples;double y=wetA&&wetB||ladder?a.y+rise*t:Math.max(a.y,b.y);var at=new Vec3(a.x+(b.x-a.x)*t,y,a.z+(b.z-a.z)*t);if(!clear(at,pose,wet)){if(pose==Pose.STANDING&&clear(at,Pose.CROUCHING,wet))pose=Pose.CROUCHING;else return null;}}
        boolean door=false;for(var pos:BlockPos.betweenClosed(BlockPos.containing(b),BlockPos.containing(b).above()))if(loaded(pos)&&NativeSurfaceNavigation.openable(player,pos,player.level().getBlockState(pos)))door=true;
        Action action=ladder&&Math.abs(rise)>.1?Action.CLIMB:wetA&&wetB?Action.SWIM:wetB?Action.ENTER_WATER:wetA?Action.LEAVE_WATER:door?Action.OPEN_DOOR:pose==Pose.CROUCHING?Action.CROUCH:rise>.65?Action.JUMP:rise>.05?Action.STEP_UP:rise< -.65?Action.DROP:Action.WALK;
        return new PathStep(from,to,action,pose==Pose.SWIMMING?Posture.SWIMMING:pose==Pose.CROUCHING?Posture.CROUCHING:Posture.STANDING,1+Math.abs(rise)*.8+(wet?1.5:0)+(pose==Pose.CROUCHING?.5:0));
    }
    @Override public List<PathStep> neighbors(Node from){return sliceCache.computeIfAbsent(from,n->{
        var nexts=new LinkedHashSet<Node>();for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})nexts.addAll(positions(n.x()+d[0],n.z()+d[1],n.y()));
        if(climb(n)||water(n)){nexts.add(new Node(n.x(),n.y16()+16,n.z()));nexts.add(new Node(n.x(),n.y16()-16,n.z()));}
        var edges=new ArrayList<PathStep>();for(var to:nexts){if(point(to).distanceToSqr(origin)>160*160)continue;var edge=transition(n,to);if(edge!=null)edges.add(edge);}return List.copyOf(edges);
    });}
}
