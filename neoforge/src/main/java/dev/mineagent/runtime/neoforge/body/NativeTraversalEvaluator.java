package dev.mineagent.runtime.neoforge.body;

import dev.mineagent.runtime.core.task.SurfacePathfinder.*;
import dev.mineagent.runtime.core.task.TerrainPathSearch;
import dev.mineagent.runtime.neoforge.skill.NativeHumanDuel;
import dev.mineagent.runtime.neoforge.skill.PvpMapArena;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;
import java.util.*;

/** Shared native graph. Caches are confined to a slice/tick; execution uses a fresh evaluator. */
public final class NativeTraversalEvaluator implements dev.mineagent.runtime.core.task.SurfacePathfinder.TraversalEvaluator {
    private static final int[][] DIRECTIONS={{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
    private final ServerPlayer player;private final Object level;private final Vec3 origin;private boolean unloaded;
    private final Map<Node,List<PathStep>> edges=new HashMap<>();
    private final Map<Node,List<Node>> floors=new HashMap<>();
    private final Map<BlockPos,BlockState> states=new HashMap<>();
    private final Map<BlockPos,List<AABB>> shapes=new HashMap<>();
    private final Map<BlockPos,TerrainPathSearch.Kind> edits;
    private long cacheTick=Long.MIN_VALUE;private int maxDrop;
    public NativeTraversalEvaluator(ServerPlayer player){this(player,Map.of());}
    public NativeTraversalEvaluator(ServerPlayer player,Map<BlockPos,TerrainPathSearch.Kind> edits){this.player=player;level=player.level();origin=player.position();this.edits=Map.copyOf(edits);beginSlice();}
    public void beginSlice(){edges.clear();floors.clear();states.clear();shapes.clear();cacheTick=player.level().getGameTime();maxDrop=NativeDropSafety.maximum(player);}
    private void fresh(){if(cacheTick!=player.level().getGameTime())beginSlice();}
    private BlockState state(BlockPos pos){fresh();return states.computeIfAbsent(pos.immutable(),p->switch(edits.get(p)){case BREAK->Blocks.AIR.defaultBlockState();case PLACE->Blocks.WHITE_WOOL.defaultBlockState();case null->player.level().getBlockState(p);});}
    private List<AABB> shapes(BlockPos pos){return shapes.computeIfAbsent(pos.immutable(),p->state(p).getCollisionShape(player.level(),p,CollisionContext.of(player)).toAabbs().stream().map(b->b.move(p)).toList());}
    public boolean current(){return player.level()==level&&player.isAlive()&&!player.isRemoved();}
    @Override public boolean encounteredUnloaded(){return unloaded;}
    public static Vec3 point(Node n){return new Vec3(n.x()+.5,n.y(),n.z()+.5);}
    // Collision reads intentionally do not call woolAction/field (the editing boundary).
    public boolean loaded(BlockPos p){if(!player.level().hasChunkAt(p)){unloaded=true;return false;}return !player.level().isOutsideBuildHeight(p)&&player.level().getWorldBorder().isWithinBounds(p);}
    public boolean water(Node n){var p=BlockPos.containing(point(n));return loaded(p)&&state(p).getFluidState().is(FluidTags.WATER);}
    public boolean climb(Node n){var p=BlockPos.containing(point(n));return loaded(p)&&state(p).is(BlockTags.CLIMBABLE);}
    private static boolean hazard(BlockState s){return s.is(Blocks.SWEET_BERRY_BUSH)||s.is(Blocks.WITHER_ROSE)||s.is(Blocks.CAMPFIRE)||s.is(Blocks.SOUL_CAMPFIRE)||s.is(Blocks.FIRE)||s.is(Blocks.SOUL_FIRE)||s.is(Blocks.CACTUS)||s.is(Blocks.POWDER_SNOW);}
    public boolean clear(Vec3 at,Pose pose,boolean allowWater){
        fresh();var box=player.getDimensions(pose).makeBoundingBox(at).deflate(.0001);
        if(NativeHumanDuel.pvpParticipant(player)&&!PvpMapArena.walkable(at,player.getBbWidth()/2))return false;
        for(var pos:BlockPos.betweenClosed((int)Math.floor(box.minX),(int)Math.floor(box.minY),(int)Math.floor(box.minZ),(int)Math.floor(box.maxX),(int)Math.floor(box.maxY),(int)Math.floor(box.maxZ))){
            if(!loaded(pos))return false;var s=state(pos);
            if(hazard(s)||!s.getFluidState().isEmpty()&&(!allowWater||!s.getFluidState().is(FluidTags.WATER)))return false;
            if(NativeSurfaceNavigation.openable(player,pos,s))continue;
            for(var shape:shapes(pos))if(shape.intersects(box))return false;
        }return true;
    }
    public List<Node> positions(int x,int z,double near){
        fresh();var key=new Node(x,(int)Math.round(near*16),z);var cached=floors.get(key);if(cached!=null)return cached;
        var nodes=new LinkedHashSet<Node>();
        if(NativeHumanDuel.pvpParticipant(player)&&!PvpMapArena.walkCell(x,z))return List.of();
        for(int y=(int)Math.floor(near)-maxDrop-1;y<=(int)Math.floor(near)+1;y++){
            var pos=new BlockPos(x,y,z);if(!loaded(pos))continue;var s=state(pos);
            if(hazard(s)||!s.getFluidState().isEmpty()||s.is(Blocks.MAGMA_BLOCK)||s.getBlock() instanceof DoorBlock||s.getBlock() instanceof FenceGateBlock)continue;
            for(var b:shapes(pos)){
                if(b.maxX<x+.22||b.minX>x+.78||b.maxZ<z+.22||b.minZ>z+.78)continue;
                double foot=b.maxY;var at=new Vec3(x+.5,foot,z+.5);
                if(foot<near-maxDrop-.01||foot>near+1.251||!clear(at,Pose.STANDING,false)&&!clear(at,Pose.CROUCHING,false))continue;
                nodes.add(new Node(x,(int)Math.round(foot*16),z));
            }
        }
        for(int y=(int)Math.floor(near)-1;y<=(int)Math.floor(near)+1;y++){
            var n=new Node(x,y*16,z);if(water(n)&&clear(point(n),Pose.SWIMMING,true)||climb(n)&&clear(point(n),Pose.STANDING,true))nodes.add(n);
        }
        var result=List.copyOf(nodes);floors.put(key,result);return result;
    }
    public Node closest(Vec3 target){return positions((int)Math.floor(target.x),(int)Math.floor(target.z),target.y).stream().min(Comparator.comparingDouble(n->Math.abs(n.y()-target.y))).orElse(null);}
    public PathStep transition(Node from,Node to){
        fresh();var a=point(from);var b=point(to);double rise=b.y-a.y;boolean wetA=water(from),wetB=water(to),ladder=climb(from)||climb(to),wet=wetA||wetB;
        int dx=Math.abs(from.x()-to.x()),dz=Math.abs(from.z()-to.z());boolean diagonal=dx==1&&dz==1;
        if(dx>1||dz>1||dx+dz==0&&!ladder&&!wetA||rise>1.251||!NativeDropSafety.affordable(player,-rise))return null;
        Pose pose=wetA&&wetB?Pose.SWIMMING:Pose.STANDING;
        if(!clear(b,pose,wet)){if(!wet&&clear(b,Pose.CROUCHING,false))pose=Pose.CROUCHING;else return null;}
        int samples=Math.max(1,(int)Math.ceil(a.distanceTo(b)*8));
        for(int i=0;i<=samples;i++){double t=i/(double)samples;double y=wetA&&wetB||ladder?a.y+rise*t:Math.max(a.y,b.y);var at=new Vec3(a.x+(b.x-a.x)*t,y,a.z+(b.z-a.z)*t);if(!clear(at,pose,wet)){if(pose==Pose.STANDING&&clear(at,Pose.CROUCHING,wet))pose=Pose.CROUCHING;else return null;}}
        if(rise<-.65&&!wet&&!ladder){
            for(double y=a.y;y>b.y;y-=.2)if(!clear(new Vec3(b.x,y,b.z),pose,false))return null;
            if(!NativeDropSafety.landing(player,b))return null;
        }
        if(diagonal){
            var x=closest(new Vec3(b.x,a.y,a.z));var z=closest(new Vec3(a.x,a.y,b.z));
            if(x==null||z==null||Math.abs(x.y()-a.y)>.1||Math.abs(z.y()-a.y)>.1)return null;
        }
        boolean door=false;for(var pos:BlockPos.betweenClosed(BlockPos.containing(b),BlockPos.containing(b).above()))if(loaded(pos)&&NativeSurfaceNavigation.openable(player,pos,state(pos)))door=true;
        Action action=ladder&&Math.abs(rise)>.1?Action.CLIMB:wetA&&wetB?Action.SWIM:wetB?Action.ENTER_WATER:wetA?Action.LEAVE_WATER:door?Action.OPEN_DOOR:pose==Pose.CROUCHING?Action.CROUCH:rise>.65?Action.JUMP:rise>.05?Action.STEP_UP:rise<-.65?Action.DROP:Action.WALK;
        return new PathStep(from,to,action,pose==Pose.SWIMMING?Posture.SWIMMING:pose==Pose.CROUCHING?Posture.CROUCHING:Posture.STANDING,(diagonal?Math.sqrt(2):1)+Math.max(0,rise)*.8+Math.max(0,-rise)*.12+NativeDropSafety.damage(player,-rise)*.8+(wet?1.5:0)+(pose==Pose.CROUCHING?.5:0));
    }
    @Override public List<PathStep> neighbors(Node from){fresh();return edges.computeIfAbsent(from,n->{
        var nexts=new LinkedHashSet<Node>();for(int[] d:DIRECTIONS)nexts.addAll(positions(n.x()+d[0],n.z()+d[1],n.y()));
        if(climb(n)||water(n)){nexts.add(new Node(n.x(),n.y16()+16,n.z()));nexts.add(new Node(n.x(),n.y16()-16,n.z()));}
        var result=new ArrayList<PathStep>();for(var to:nexts){if(point(to).distanceToSqr(origin)>160*160)continue;var edge=transition(n,to);if(edge!=null)result.add(edge);}return List.copyOf(result);
    });}
    /** Same checked straight-line grid walk as legacy189, bounded before falling back to A*. */
    public List<PathStep> direct(Node start,Node end,java.util.function.BooleanSupplier budget){
        var route=new ArrayList<PathStep>();var at=start;int count=Math.max(Math.abs(end.x()-start.x()),Math.abs(end.z()-start.z()));
        if(count>24||count==0&&!start.equals(end))return null;
        for(int i=1;i<=count;i++){
            if(!budget.getAsBoolean())return null;
            int x=start.x()+(int)Math.round((end.x()-start.x())*i/(double)count),z=start.z()+(int)Math.round((end.z()-start.z())*i/(double)count);
            PathStep best=null;double cost=Double.POSITIVE_INFINITY;
            for(var next:positions(x,z,at.y())){var step=transition(at,next);if(step==null)continue;double score=step.cost()+Math.abs(next.y()-end.y())*.12;if(score<cost){cost=score;best=step;}}
            if(best==null)return null;route.add(best);at=best.to();
        }return at.equals(end)?List.copyOf(route):null;
    }
}
