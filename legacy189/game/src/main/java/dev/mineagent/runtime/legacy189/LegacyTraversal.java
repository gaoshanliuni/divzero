package dev.mineagent.runtime.legacy189;

import dev.mineagent.runtime.legacy189.navigation.SurfacePathfinder.*;
import net.minecraft.block.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.*;
import java.util.*;

/** 1.8.9 collision adapter for 26.1.2 NativeSurfaceNavigation/NativeTraversalEvaluator.
 * Keeps sixteenth-block floors, sampled transitions and typed movement actions. */
public final class LegacyTraversal implements TraversalEvaluator {
    private final NativeAgent player;
    private final Vec3 origin;
    private final Map<Node, List<PathStep>> cache = new HashMap<Node, List<PathStep>>();
    private boolean unloaded;
    public LegacyTraversal(NativeAgent player) { this.player = player; origin = player.getPositionVector(); }
    public void beginSlice() { cache.clear(); }
    @Override public boolean encounteredUnloaded() { return unloaded; }
    public static Vec3 point(Node n) { return new Vec3(n.x() + .5, n.y(), n.z() + .5); }
    public boolean loaded(BlockPos pos) {
        if (!player.worldObj.isBlockLoaded(pos)) { unloaded = true; return false; }
        return pos.getY() >= 0 && pos.getY() < 256 && pos.getX() >= -16 && pos.getX() <= 16 && pos.getZ() >= 784 && pos.getZ() <= 816;
    }
    public static boolean hazard(Block block) { return block == Blocks.fire || block == Blocks.cactus || block.getMaterial() == net.minecraft.block.material.Material.lava; }
    private List<AxisAlignedBB> shapes(BlockPos pos, AxisAlignedBB query) {
        List<AxisAlignedBB> boxes = new ArrayList<AxisAlignedBB>(); IBlockState state = player.worldObj.getBlockState(pos);
        state.getBlock().addCollisionBoxesToList(player.worldObj, pos, state, query, boxes, player); return boxes;
    }
    public boolean openable(BlockPos pos) {
        IBlockState state = player.worldObj.getBlockState(pos); Block block = state.getBlock();
        if (block instanceof BlockDoor && block.getMaterial() == net.minecraft.block.material.Material.wood)
            return !((Boolean) block.getActualState(state, player.worldObj, pos).getValue(BlockDoor.OPEN));
        return block instanceof BlockFenceGate && !((Boolean) state.getValue(BlockFenceGate.OPEN));
    }
    public boolean clear(Vec3 at, boolean water) {
        // 1.8.9 has no modern 1.5-block crouching/swimming pose: use its actual body bounds.
        double half = player.width / 2d - .0001;
        AxisAlignedBB box = new AxisAlignedBB(at.xCoord-half,at.yCoord+.0001,at.zCoord-half,at.xCoord+half,at.yCoord+player.height-.0001,at.zCoord+half);
        for (int x=(int)Math.floor(box.minX);x<=Math.floor(box.maxX);x++) for (int y=(int)Math.floor(box.minY);y<=Math.floor(box.maxY);y++) for (int z=(int)Math.floor(box.minZ);z<=Math.floor(box.maxZ);z++) {
            BlockPos pos = new BlockPos(x,y,z); if (!loaded(pos)) return false;
            Block block=player.worldObj.getBlockState(pos).getBlock();
            if (hazard(block) || block.getMaterial().isLiquid() && !(water && block.getMaterial()==net.minecraft.block.material.Material.water)) return false;
            if (!openable(pos) && !shapes(pos,box).isEmpty()) return false;
        }
        return true;
    }
    public boolean water(Node node) { BlockPos pos=new BlockPos(point(node));return loaded(pos)&&player.worldObj.getBlockState(pos).getBlock().getMaterial()==net.minecraft.block.material.Material.water; }
    public boolean climb(Node node) { BlockPos pos=new BlockPos(point(node));return loaded(pos)&&player.worldObj.getBlockState(pos).getBlock().isLadder(player.worldObj,pos,player); }
    public List<Node> positions(int x,int z,double near) {
        Set<Node> nodes=new LinkedHashSet<Node>();
        for(int y=(int)Math.floor(near)-4;y<=(int)Math.floor(near)+1;y++) {
            BlockPos pos=new BlockPos(x,y,z);if(!loaded(pos))continue;Block block=player.worldObj.getBlockState(pos).getBlock();
            if(hazard(block)||block instanceof BlockDoor||block instanceof BlockFenceGate)continue;
            for(AxisAlignedBB shape:shapes(pos,new AxisAlignedBB(x,y,z,x+1,y+2,z+1))) {
                if(shape.maxX<x+.22||shape.minX>x+.78||shape.maxZ<z+.22||shape.minZ>z+.78)continue;
                double foot=shape.maxY;if(foot<near-3.01||foot>near+1.251||!clear(new Vec3(x+.5,foot,z+.5),false))continue;
                nodes.add(new Node(x,(int)Math.round(foot*16),z));
            }
        }
        for(int y=(int)Math.floor(near)-1;y<=(int)Math.floor(near)+1;y++) {
            Node n=new Node(x,y*16,z);if((water(n)||climb(n))&&clear(point(n),true))nodes.add(n);
        }
        return new ArrayList<Node>(nodes);
    }
    public Node closest(Vec3 at) { Node best=null;for(Node n:positions((int)Math.floor(at.xCoord),(int)Math.floor(at.zCoord),at.yCoord))if(best==null||Math.abs(n.y()-at.yCoord)<Math.abs(best.y()-at.yCoord))best=n;return best; }
    public PathStep transition(Node from,Node to) {
        Vec3 a=point(from),b=point(to);double rise=b.yCoord-a.yCoord;boolean wetA=water(from),wetB=water(to),ladder=climb(from)||climb(to),wet=wetA||wetB;
        int horizontal=Math.abs(from.x()-to.x())+Math.abs(from.z()-to.z());
        if(horizontal>1||horizontal==0&&!ladder&&!wetA||rise>1.251||rise< -3||!clear(b,wet))return null;
        int samples=Math.max(1,(int)Math.ceil(a.distanceTo(b)*8));
        for(int i=0;i<=samples;i++){double t=i/(double)samples,y=wetA&&wetB||ladder?a.yCoord+rise*t:Math.max(a.yCoord,b.yCoord);if(!clear(new Vec3(a.xCoord+(b.xCoord-a.xCoord)*t,y,a.zCoord+(b.zCoord-a.zCoord)*t),wet))return null;}
        boolean door=openable(new BlockPos(b))||openable(new BlockPos(b).up());
        Action action=ladder&&Math.abs(rise)>.1?Action.CLIMB:wetA&&wetB?Action.SWIM:wetB?Action.ENTER_WATER:wetA?Action.LEAVE_WATER:door?Action.OPEN_DOOR:rise>.65?Action.JUMP:rise>.05?Action.STEP_UP:rise< -.65?Action.DROP:Action.WALK;
        return new PathStep(from,to,action,wetA&&wetB?Posture.SWIMMING:Posture.STANDING,1+Math.abs(rise)*.8+(wet?1.5:0));
    }
    @Override public List<PathStep> neighbors(Node from) {
        List<PathStep> old=cache.get(from);if(old!=null)return old;
        Set<Node> nexts=new LinkedHashSet<Node>();for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})nexts.addAll(positions(from.x()+d[0],from.z()+d[1],from.y()));
        if(climb(from)||water(from)){nexts.add(new Node(from.x(),from.y16()+16,from.z()));nexts.add(new Node(from.x(),from.y16()-16,from.z()));}
        List<PathStep> edges=new ArrayList<PathStep>();for(Node next:nexts){if(point(next).squareDistanceTo(origin)>160*160)continue;PathStep step=transition(from,next);if(step!=null)edges.add(step);}
        cache.put(from,edges);return edges;
    }
    public boolean openOnPath(Vec3 waypoint) {
        for(BlockPos pos:new BlockPos[]{new BlockPos(waypoint),new BlockPos(waypoint).up()})if(openable(pos)) {
            Vec3 center=new Vec3(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5);
            if(player.getPositionEyes(1).squareDistanceTo(center)>4.5*4.5)return false;
            MovingObjectPosition hit=player.worldObj.rayTraceBlocks(player.getPositionEyes(1),center,false,true,false);
            if(hit!=null&&!hit.getBlockPos().equals(pos))return false;
            player.theItemInWorldManager.activateBlockOrUseItem(player,player.worldObj,player.getHeldItem(),pos,EnumFacing.UP,.5f,.5f,.5f);
            if(openable(pos))return false;
        }
        return true;
    }
}
