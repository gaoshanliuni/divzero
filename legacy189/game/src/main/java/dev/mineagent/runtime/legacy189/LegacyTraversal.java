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
    private final Map<BlockPos,IBlockState> states=new HashMap<BlockPos,IBlockState>();
    private final Map<Node,List<Node>> floors=new HashMap<Node,List<Node>>();
    private final Map<BlockPos,List<AxisAlignedBB>> collisionShapes=new HashMap<BlockPos,List<AxisAlignedBB>>();
    private boolean unloaded;
    private final Map<BlockPos,dev.mineagent.runtime.legacy189.navigation.TerrainPathSearch.Kind> edits;
    public LegacyTraversal(NativeAgent player) { this(player,Collections.emptyMap()); }
    public LegacyTraversal(NativeAgent player,Map<BlockPos,dev.mineagent.runtime.legacy189.navigation.TerrainPathSearch.Kind> edits) { this.player = player; origin = player.getPositionVector();this.edits=edits; }
    private IBlockState state(BlockPos pos){dev.mineagent.runtime.legacy189.navigation.TerrainPathSearch.Kind edit=edits.get(pos);if(edit!=null)return edit==dev.mineagent.runtime.legacy189.navigation.TerrainPathSearch.Kind.BREAK?Blocks.air.getDefaultState():Blocks.wool.getDefaultState();IBlockState value=states.get(pos);if(value==null){value=player.worldObj.getBlockState(pos);states.put(pos,value);}return value;}
    public void beginSlice() { cache.clear();states.clear();floors.clear();collisionShapes.clear(); }
    @Override public boolean encounteredUnloaded() { return unloaded; }
    public static Vec3 point(Node n) { return new Vec3(n.x() + .5, n.y(), n.z() + .5); }
    public boolean loaded(BlockPos pos) {
        if (!player.worldObj.isBlockLoaded(pos)) { unloaded = true; return false; }
        return pos.getY() >= 0 && pos.getY() < 256 && pos.getX() >= -19 && pos.getX() <= 19 && pos.getZ() >= 782 && pos.getZ() <= 819;
    }
    public static boolean hazard(Block block) { return block == Blocks.fire || block == Blocks.cactus || block.getMaterial() == net.minecraft.block.material.Material.lava; }
    private List<AxisAlignedBB> shapes(BlockPos pos, AxisAlignedBB query) {
        List<AxisAlignedBB> boxes = new ArrayList<AxisAlignedBB>(); IBlockState state = state(pos);
        if(edits.containsKey(pos)){if(state.getBlock()==Blocks.wool){AxisAlignedBB box=new AxisAlignedBB(pos.getX(),pos.getY(),pos.getZ(),pos.getX()+1,pos.getY()+1,pos.getZ()+1);if(box.intersectsWith(query))boxes.add(box);}return boxes;}
        List<AxisAlignedBB> all=collisionShapes.get(pos);
        if(all==null){all=new ArrayList<AxisAlignedBB>();state.getBlock().addCollisionBoxesToList(player.worldObj,pos,state,new AxisAlignedBB(pos.getX()-1,pos.getY()-1,pos.getZ()-1,pos.getX()+2,pos.getY()+3,pos.getZ()+2),all,player);collisionShapes.put(pos,all);}
        for(AxisAlignedBB shape:all)if(shape.intersectsWith(query))boxes.add(shape);return boxes;
    }
    public boolean openable(BlockPos pos) {
        IBlockState state = state(pos); Block block = state.getBlock();
        if (block instanceof BlockDoor && block.getMaterial() == net.minecraft.block.material.Material.wood)
            return !((Boolean) block.getActualState(state, player.worldObj, pos).getValue(BlockDoor.OPEN));
        return block instanceof BlockFenceGate && !((Boolean) state.getValue(BlockFenceGate.OPEN));
    }
    public boolean clear(Vec3 at, boolean water) {
        if(at.xCoord< -17.7||at.xCoord>18.7||at.zCoord<783.3||at.zCoord>818.7)return false;
        // 1.8.9 has no modern 1.5-block crouching/swimming pose: use its actual body bounds.
        double half = player.width / 2d - .0001;
        AxisAlignedBB box = new AxisAlignedBB(at.xCoord-half,at.yCoord+.0001,at.zCoord-half,at.xCoord+half,at.yCoord+player.height-.0001,at.zCoord+half);
        for (int x=(int)Math.floor(box.minX);x<=Math.floor(box.maxX);x++) for (int y=(int)Math.floor(box.minY);y<=Math.floor(box.maxY);y++) for (int z=(int)Math.floor(box.minZ);z<=Math.floor(box.maxZ);z++) {
            BlockPos pos = new BlockPos(x,y,z); if (!loaded(pos)) return false;
            Block block=state(pos).getBlock();
            if (hazard(block) || block.getMaterial().isLiquid() && !(water && block.getMaterial()==net.minecraft.block.material.Material.water)) return false;
            if (!openable(pos) && !shapes(pos,box).isEmpty()) return false;
        }
        return true;
    }
    public boolean water(Node node) { BlockPos pos=new BlockPos(point(node));return loaded(pos)&&state(pos).getBlock().getMaterial()==net.minecraft.block.material.Material.water; }
    public boolean climb(Node node) { BlockPos pos=new BlockPos(point(node));return loaded(pos)&&state(pos).getBlock().isLadder(player.worldObj,pos,player); }
    public List<Node> positions(int x,int z,double near) {
        if(!NativeArena.walkCell(x,z))return Collections.emptyList();
        Node key=new Node(x,(int)Math.round(near*16),z);List<Node> cached=floors.get(key);if(cached!=null)return cached;
        Set<Node> nodes=new LinkedHashSet<Node>();
        int descent=maximumDrop();
        for(int y=(int)Math.floor(near)-descent-1;y<=(int)Math.floor(near)+1;y++) {
            BlockPos pos=new BlockPos(x,y,z);if(!loaded(pos))continue;Block block=state(pos).getBlock();
            if(hazard(block)||block instanceof BlockDoor||block instanceof BlockFenceGate)continue;
            for(AxisAlignedBB shape:shapes(pos,new AxisAlignedBB(x,y,z,x+1,y+2,z+1))) {
                if(shape.maxX<x+.22||shape.minX>x+.78||shape.maxZ<z+.22||shape.minZ>z+.78)continue;
                double foot=shape.maxY;if(foot<near-descent-.01||foot>near+1.251||!clear(new Vec3(x+.5,foot,z+.5),false))continue;
                nodes.add(new Node(x,(int)Math.round(foot*16),z));
            }
        }
        for(int y=(int)Math.floor(near)-1;y<=(int)Math.floor(near)+1;y++) {
            Node n=new Node(x,y*16,z);if((water(n)||climb(n))&&clear(point(n),true))nodes.add(n);
        }
        List<Node> result=new ArrayList<Node>(nodes);floors.put(key,result);return result;
    }
    public Node closest(Vec3 at) { Node best=null;for(Node n:positions((int)Math.floor(at.xCoord),(int)Math.floor(at.zCoord),at.yCoord))if(best==null||Math.abs(n.y()-at.yCoord)<Math.abs(best.y()-at.yCoord))best=n;return best; }
    public PathStep transition(Node from,Node to) {
        Vec3 a=point(from),b=point(to);double rise=b.yCoord-a.yCoord;boolean wetA=water(from),wetB=water(to),ladder=climb(from)||climb(to),wet=wetA||wetB;
        int horizontal=Math.abs(from.x()-to.x())+Math.abs(from.z()-to.z());
        boolean diagonal=Math.abs(from.x()-to.x())==1&&Math.abs(from.z()-to.z())==1;
        if(horizontal>1&&!diagonal||horizontal==0&&!ladder&&!wetA||rise>1.251||!acceptableDrop(-rise)||!clear(b,wet))return null;
        int samples=Math.max(1,(int)Math.ceil(a.distanceTo(b)*8));
        for(int i=0;i<=samples;i++){double t=i/(double)samples,y=wetA&&wetB||ladder?a.yCoord+rise*t:Math.max(a.yCoord,b.yCoord);if(!clear(new Vec3(a.xCoord+(b.xCoord-a.xCoord)*t,y,a.zCoord+(b.zCoord-a.zCoord)*t),wet))return null;}
        if(rise<-.65&&!wet&&!ladder)for(double y=a.yCoord;y>b.yCoord;y-=.2)if(!clear(new Vec3(b.xCoord,y,b.zCoord),false))return null;
        if(diagonal){
            // No cutting unsupported/blocked corners on narrow rims or stair edges.
            Node x=closest(new Vec3(b.xCoord,a.yCoord,a.zCoord)),z=closest(new Vec3(a.xCoord,a.yCoord,b.zCoord));
            if(x==null||z==null||Math.abs(x.y()-a.yCoord)>.1||Math.abs(z.y()-a.yCoord)>.1)return null;
        }
        boolean door=openable(new BlockPos(b))||openable(new BlockPos(b).up());
        Action action=ladder&&Math.abs(rise)>.1?Action.CLIMB:wetA&&wetB?Action.SWIM:wetB?Action.ENTER_WATER:wetA?Action.LEAVE_WATER:door?Action.OPEN_DOOR:rise>.65?Action.JUMP:rise>.05?Action.STEP_UP:rise< -.65?Action.DROP:Action.WALK;
        return new PathStep(from,to,action,wetA&&wetB?Posture.SWIMMING:Posture.STANDING,(diagonal?Math.sqrt(2):1)+Math.max(0,rise)*.8+Math.max(0,-rise)*.12+dropDamage(-rise)*.8+(wet?1.5:0));
    }
    public double dropDamage(double height){
        int boost=player.isPotionActive(net.minecraft.potion.Potion.jump)?player.getActivePotionEffect(net.minecraft.potion.Potion.jump).getAmplifier()+1:0;
        return ModernCombat.protection(player,DamageSource.fall,(float)Math.max(0,Math.ceil(height-3-boost)));
    }
    public boolean acceptableDrop(double height){double damage=dropDamage(height);return height<=24&&(damage<=0||damage<=3&&damage<=player.getHealth()*.25&&player.getHealth()+player.getAbsorptionAmount()-damage>=4);}
    private int maximumDrop(){int height=3;while(height<24&&acceptableDrop(height+1))height++;return height;}
    @Override public List<PathStep> neighbors(Node from) {
        List<PathStep> old=cache.get(from);if(old!=null)return old;
        Set<Node> nexts=new LinkedHashSet<Node>();for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}})nexts.addAll(positions(from.x()+d[0],from.z()+d[1],from.y()));
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
    public boolean rayClear(Vec3 from,Vec3 to){
        for(Map.Entry<BlockPos,dev.mineagent.runtime.legacy189.navigation.TerrainPathSearch.Kind> entry:edits.entrySet())if(entry.getValue()==dev.mineagent.runtime.legacy189.navigation.TerrainPathSearch.Kind.PLACE){BlockPos p=entry.getKey();AxisAlignedBB box=new AxisAlignedBB(p.getX(),p.getY(),p.getZ(),p.getX()+1,p.getY()+1,p.getZ()+1);if(box.isVecInside(from)||box.calculateIntercept(from,to)!=null)return false;}
        Vec3 at=from,unit=to.subtract(from).normalize();
        for(int i=0;i<32;i++){
            MovingObjectPosition hit=player.worldObj.rayTraceBlocks(at,to,false,true,false);if(hit==null)return true;
            if(edits.get(hit.getBlockPos())!=dev.mineagent.runtime.legacy189.navigation.TerrainPathSearch.Kind.BREAK)return false;
            Vec3 next=hit.hitVec.addVector(unit.xCoord*.002,unit.yCoord*.002,unit.zCoord*.002);
            if(next.squareDistanceTo(to)<.00001)return true;if(next.squareDistanceTo(at)<.000001)return false;at=next;
        }return false;
    }
}
