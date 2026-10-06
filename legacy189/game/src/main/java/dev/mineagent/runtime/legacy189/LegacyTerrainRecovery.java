package dev.mineagent.runtime.legacy189;

import dev.mineagent.runtime.legacy189.navigation.TerrainPathSearch;
import dev.mineagent.runtime.legacy189.navigation.TerrainPathSearch.*;
import dev.mineagent.runtime.legacy189.navigation.SurfacePathfinder;
import dev.mineagent.runtime.legacy189.navigation.SurfacePathfinder.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;
import java.util.*;

/** Port of 26.1.2 NativeTerrainRecovery/NativeTerrainPolicy's PvP branch.
 * Execute one observed edit/move, then recheck the original surface route.
 * Permanent map blocks stay protected; only real carried wool can be placed. */
public final class LegacyTerrainRecovery {
    private TerrainPathSearch search;
    private TerrainPathSearch.Step planned;
    private final Deque<TerrainPathSearch.Step> approachSteps=new ArrayDeque<TerrainPathSearch.Step>();
    private Edit edit;
    private NativeAgent actor;
    private EntityPlayerMP target;
    private Vec3 origin, goal;
    private int started, actionAt, selectedSlot, settled;
    private boolean sent, jumped;
    private boolean pursuit;
    private String before;
    private float progress;
    private final Rejections<String> rejected=new Rejections<String>();
    public int attempts, broken, placed, consumed;
    public String state="IDLE";
    public boolean active(){return search!=null||planned!=null;}
    public void reset(NativeAgent actor){cancel();this.actor=actor;rejected.clear();attempts=broken=placed=consumed=0;goal=null;}
    public void cancel(){
        if(actor!=null){if(edit!=null)actor.worldObj.sendBlockBreakProgress(actor.getEntityId(),pos(edit.cell()),-1);if(planned!=null)actor.inventory.currentItem=selectedSlot;}
        search=null;planned=null;edit=null;approachSteps.clear();state="IDLE";progress=0;
    }
    private static BlockPos pos(Cell c){return new BlockPos(c.x(),c.y(),c.z());}
    private static Cell cell(Vec3 at){return new Cell((int)Math.floor(at.xCoord),(int)Math.floor(at.yCoord),(int)Math.floor(at.zCoord));}
    private static Node node(Cell c){return new Node(c.x(),c.y()*16,c.z());}
    private static Vec3 point(Cell c){return new Vec3(c.x()+.5,c.y(),c.z()+.5);}
    private boolean loaded(BlockPos p){return actor.worldObj.isBlockLoaded(p)&&p.getY()>=0&&p.getY()<256;}
    private boolean mayBreak(BlockPos p){return LegacyArenaMaterials.editable(actor.worldObj,p);}
    private boolean mayPlace(BlockPos p){return NativeArena.field(p)&&loaded(p)&&actor.worldObj.isAirBlock(p);}
    private String signature(BlockPos p){return p.toString()+":"+actor.worldObj.getBlockState(p).toString();}
    private int materialSlot(){for(int i=0;i<36;i++){ItemStack stack=actor.inventory.mainInventory[i];if(stack!=null&&stack.stackSize>0&&net.minecraft.block.Block.getBlockFromItem(stack.getItem())==Blocks.wool)return i;}return -1;}
    private int toolSlot(BlockPos p){int best=actor.inventory.currentItem;float speed=0;for(int i=0;i<36;i++){ItemStack stack=actor.inventory.mainInventory[i];float value=stack==null?1:stack.getStrVsBlock(actor.worldObj.getBlockState(p).getBlock());if(value>speed&&(stack==null||!stack.isItemStackDamageable()||stack.getItemDamage()<stack.getMaxDamage()-1)){best=i;speed=value;}}return best;}
    private void select(int slot){if(slot<9)actor.inventory.currentItem=slot;else{ItemStack old=actor.inventory.mainInventory[0];actor.inventory.mainInventory[0]=actor.inventory.mainInventory[slot];actor.inventory.mainInventory[slot]=old;actor.inventory.currentItem=0;}actor.inventoryContainer.detectAndSendChanges();}
    private int breakTicks(BlockPos p){float value=actor.worldObj.getBlockState(p).getBlock().getPlayerRelativeBlockHardness(actor,actor.worldObj,p);return value<=0||!Float.isFinite(value)?Integer.MAX_VALUE:Math.max(1,(int)Math.ceil(1/value));}
    private String context(BlockPos at,Kind kind){
        StringBuilder result=new StringBuilder(new BlockPos(actor).toString());
        for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++){BlockPos p=at.add(x,y,z);result.append(loaded(p)?signature(p):"unloaded");}
        result.append(kind==Kind.BREAK?mayBreak(at):mayPlace(at));
        int slot=kind==Kind.BREAK?toolSlot(at):materialSlot();ItemStack stack=slot<0?null:actor.inventory.mainInventory[slot];
        return result.append(stack==null?"empty":stack.writeToNBT(new net.minecraft.nbt.NBTTagCompound()).toString()).toString();
    }
    public boolean request(NativeAgent player,EntityPlayerMP target,int tick){
        return request(player,target,tick,false);
    }
    public boolean request(NativeAgent player,EntityPlayerMP target,int tick,boolean pursueTarget){
        if(active())return true;
        if(!player.onGround||!player.isEntityAlive()||Math.abs(player.posY-Math.rint(player.posY))>.06)return false;
        Vec3 targetPoint=target.getPositionVector();
        if(actor!=player||goal==null||goal.squareDistanceTo(targetPoint)>4){rejected.clear();}
        actor=player;this.target=target;goal=targetPoint;origin=player.getPositionVector();started=tick;pursuit=pursueTarget;state=pursuit?"SEARCHING_APPROACH":"SEARCHING_ESCAPE";
        final Cell originCell=cell(origin);
        // PvP may spend real wool on a one-way column. No permanent/reusable exit is required.
        if(goal.yCoord>origin.yCoord+1.3&&Math.hypot(goal.xCoord-origin.xCoord,goal.zCoord-origin.zCoord)<3.5&&materialSlot()>=0&&mayPlace(pos(originCell))){
            LegacyTraversal check=new LegacyTraversal(actor);
            if(check.clear(origin.addVector(0,1.25,0),false)&&!rejected.contains(originCell,Kind.PLACE,()->context(pos(originCell),Kind.PLACE))){
                edit=new Edit(originCell,Kind.PLACE,signature(pos(originCell)),12);
                planned=new TerrainPathSearch.Step(originCell,originCell.add(0,1,0),Collections.singletonList(edit),true,22);
                approachSteps.clear();actionAt=tick;selectedSlot=actor.inventory.currentItem;before=signature(pos(originCell));progress=0;sent=jumped=false;settled=0;attempts++;state="PVP_VERTICAL_BUILD";return true;
            }
        }
        if(pursuit){
            Vec3 eye=actor.getPositionEyes(1);MovingObjectPosition obstruction=actor.worldObj.rayTraceBlocks(eye,target.getPositionEyes(1),false,true,false);
            if(obstruction!=null&&eye.squareDistanceTo(obstruction.hitVec)<=4.5*4.5){
                BlockPos at=obstruction.getBlockPos();Cell editCell=new Cell(at.getX(),at.getY(),at.getZ());
                if(mayBreak(at)&&breakTicks(at)<=240&&!at.equals(new BlockPos(actor).down())&&!rejected.contains(editCell,Kind.BREAK,()->context(at,Kind.BREAK))){
                    edit=new Edit(editCell,Kind.BREAK,signature(at),breakTicks(at));planned=new TerrainPathSearch.Step(originCell,originCell,Collections.singletonList(edit),false,edit.ticks()+4);
                    approachSteps.clear();actionAt=tick;selectedSlot=actor.inventory.currentItem;before=signature(at);progress=0;sent=jumped=false;attempts++;state="APPROACH_BREACH";
                    if(NativeFixture.requested())LegacyMod.logger.info("LEGACY_PURSUIT_BREACH target={} position={}",at,origin);return true;
                }
            }
        }
        TerrainPathSearch.World world=new TerrainPathSearch.World(){
            public TerrainPathSearch.Block block(Cell c){
                BlockPos p=pos(c);if(!loaded(p))return new TerrainPathSearch.Block(false,false,false,false,0,"unloaded");
                net.minecraft.block.Block block=actor.worldObj.getBlockState(p).getBlock();boolean hazard=LegacyTraversal.hazard(block)||block.getMaterial().isLiquid();
                AxisAlignedBB box=block.getCollisionBoundingBox(actor.worldObj,p,actor.worldObj.getBlockState(p));
                boolean clear=!hazard&&box==null,support=!hazard&&box!=null&&box.maxY>=p.getY()+.875&&box.minX<=p.getX()+.2&&box.maxX>=p.getX()+.8&&box.minZ<=p.getZ()+.2&&box.maxZ>=p.getZ()+.8;
                return new TerrainPathSearch.Block(true,clear,support,!rejected.contains(c,Kind.BREAK,()->context(p,Kind.BREAK))&&mayBreak(p),breakTicks(p),signature(p));
            }
            public boolean canPlace(Cell c){
                // Do not enumerate arbitrary pillars when the blocked opponent is on
                // the same floor. Horizontal gaps still permit real support blocks.
                if(pursuit&&goal.yCoord<=origin.yCoord+.5&&c.y()>=originCell.y())return false;
                return !rejected.contains(c,Kind.PLACE,()->context(pos(c),Kind.PLACE))&&mayPlace(pos(c));
            }
            public boolean exit(Cell c,Map<Cell,Kind> edits){
                if(pursuit){
                    Map<BlockPos,Kind> overlay=new HashMap<BlockPos,Kind>();for(Map.Entry<Cell,Kind> edit:edits.entrySet())overlay.put(pos(edit.getKey()),edit.getValue());
                    LegacyTraversal virtual=new LegacyTraversal(actor,overlay);Vec3 at=point(c);Node floor=virtual.closest(at);
                    if(floor==null||Math.abs(floor.y()-at.yCoord)>.251||!virtual.clear(at,false))return false;
                    Vec3 eye=at.addVector(0,actor.getEyeHeight(),0);AxisAlignedBB box=target.getEntityBoundingBox();
                    Vec3 hit=new Vec3(Math.max(box.minX+.001,Math.min(box.maxX-.001,eye.xCoord)),Math.max(box.minY+.001,Math.min(box.maxY-.001,eye.yCoord)),Math.max(box.minZ+.001,Math.min(box.maxZ-.001,eye.zCoord)));
                    if(eye.squareDistanceTo(hit)<=9&&virtual.rayClear(eye,hit)&&stableAttackStance(virtual,at,target))return true;
                    // Long approaches advance through checked terrain in bounded segments.
                    // A built support may be an intermediate attack approach, never an invented material.
                    double progress=origin.distanceTo(goal)-at.distanceTo(goal);
                    double horizontalProgress=Math.hypot(origin.xCoord-goal.xCoord,origin.zCoord-goal.zCoord)-Math.hypot(at.xCoord-goal.xCoord,at.zCoord-goal.zCoord);
                    if(edits.isEmpty()&&Math.abs(at.yCoord-origin.yCoord)<.25&&horizontalProgress>=2&&Math.hypot(at.xCoord-goal.xCoord,at.zCoord-goal.zCoord)>1.4)return true;
                    if(!edits.isEmpty()&&c.y()>originCell.y()&&goal.yCoord>origin.yCoord+.5&&progress>.15)return true;
                    return progress>=2&&at.distanceTo(goal)>3.5;
                }
                if(c.equals(originCell))return false;
                Map<BlockPos,Kind> overlay=new HashMap<BlockPos,Kind>();for(Map.Entry<Cell,Kind> entry:edits.entrySet())overlay.put(pos(entry.getKey()),entry.getValue());
                Vec3 at=point(c);LegacyTraversal check=new LegacyTraversal(actor,overlay);Node n=check.closest(at);
                if(n==null||Math.abs(n.y()-at.yCoord)>.251||!check.clear(LegacyTraversal.point(n),false))return false;
                if(c.y()>originCell.y()&&goal.yCoord>origin.yCoord+.5&&at.distanceTo(goal)<origin.distanceTo(goal)-.15)return true;
                Vec3 delta=goal.subtract(at), nextGoal=goal.squareDistanceTo(at)>256?at.addVector(delta.normalize().xCoord*16,delta.normalize().yCoord*16,delta.normalize().zCoord*16):goal;
                Node end=check.closest(nextGoal);return end!=null&&new SurfacePathfinder.Search(n,end,check).advance(192).status()==Status.FOUND;
            }
            public double risk(Cell c){double risk=Math.max(0,4-point(c).distanceTo(target.getPositionVector()))*2;return actor.getHealth()<6?risk*2:risk;}
            public double learnedCost(TerrainPathSearch.Step step,double risk){
                Vec3 delta=point(step.to()).subtract(point(step.from()));
                double[] features={actor.getHealth()/Math.max(1,actor.getMaxHealth()),Math.min(2,origin.distanceTo(goal)/16),Math.hypot(actor.motionX,actor.motionZ)/.4,0,1,
                        Math.min(1,delta.lengthVector()/8),Math.min(2,risk/80),.125,delta.xCoord/8,delta.zCoord/8,delta.yCoord/4,0,7d/8,actor.onGround?0:1,ModernCombat.baseDamage(actor)/10,
                        step.edits().stream().filter(e->e.kind()==Kind.PLACE).count()};
                return actor.policy().cost(features)*5;
            }
        };
        int slot=materialSlot();
        if(pursuit&&world.exit(originCell,Collections.emptyMap())&&actor.getPositionVector().squareDistanceTo(point(originCell))>.0001){
            planned=new TerrainPathSearch.Step(originCell,originCell,Collections.emptyList(),false,1);edit=null;actionAt=tick;selectedSlot=actor.inventory.currentItem;attempts++;state="ALIGN_ATTACK_STANCE";return true;
        }
        if(NativeFixture.requested()&&!pursuit){
            com.google.gson.JsonObject debug=new com.google.gson.JsonObject();debug.addProperty("origin",origin.toString());debug.addProperty("goal",goal.toString());debug.addProperty("materialSlot",slot);debug.addProperty("materials",slot<0?0:actor.inventory.mainInventory[slot].stackSize);
            debug.add("originBlock",new com.google.gson.Gson().toJsonTree(world.block(originCell)));debug.addProperty("canPlaceOrigin",world.canPlace(originCell));
            com.google.gson.JsonArray exits=new com.google.gson.JsonArray();
            for(int[] d:new int[][]{{-1,0},{1,0},{0,-1},{0,1}}){
                Cell c=originCell.add(d[0],2,d[1]);LegacyTraversal check=new LegacyTraversal(actor);Node n=check.closest(point(c)),end=check.closest(goal);
                com.google.gson.JsonObject exit=new com.google.gson.JsonObject();exit.add("cell",new com.google.gson.Gson().toJsonTree(c));exit.add("floor",new com.google.gson.Gson().toJsonTree(world.block(c.add(0,-1,0))));exit.add("node",new com.google.gson.Gson().toJsonTree(n));exit.add("end",new com.google.gson.Gson().toJsonTree(end));exit.addProperty("neighbors",n==null?-1:check.neighbors(n).size());exit.addProperty("route",n==null||end==null?"MISSING_NODE":new SurfacePathfinder.Search(n,end,check).advance(96).status().name());exit.addProperty("exit",world.exit(c,Collections.emptyMap()));exits.add(exit);
            }
            debug.add("exitProbes",exits);LegacyMod.logger.info("LEGACY_RECOVERY_FIXTURE_CONTEXT={}",debug);
        }
        search=new TerrainPathSearch(world,originCell,slot<0?0:actor.inventory.mainInventory[slot].stackSize,6);return true;
    }
    /** changed[0] requests immediate re-evaluation of the original route. */
    public PathStep tick(int tick,boolean[] changed){
        if(!active())return null;
        if(!actor.isEntityAlive()||actor.worldObj!=target.worldObj||goal.squareDistanceTo(target.getPositionVector())>4){cancel();return null;}
        if(tick-started>300){fail("ESCAPE_CONTEXT_RECHECK_TIMEOUT");return null;}
        if(search!=null){
            final long deadline=System.nanoTime()+2_000_000L;
            TerrainPathSearch.Result found=search.advance(192,()->System.nanoTime()<deadline);
            if(found.state().equals("SEARCHING"))return null;
            search=null;if(!found.state().equals("FOUND")){state=found.state();if(NativeFixture.requested())LegacyMod.logger.info("LEGACY_TERRAIN_SEARCH_END state={} expanded={} origin={} goal={}",state,found.expanded(),origin,goal);return null;}
            planned=found.steps().get(0);approachSteps.clear();if(pursuit)for(int i=1;i<found.steps().size();i++)approachSteps.addLast(found.steps().get(i));edit=planned.edits().isEmpty()?null:planned.edits().get(0);
            actionAt=tick;selectedSlot=actor.inventory.currentItem;sent=jumped=false;settled=0;progress=0;attempts++;
            state=edit==null?"ESCAPE_WALK":edit.kind()==Kind.BREAK?"ESCAPE_MINE":"ESCAPE_PLACE";
            if(edit!=null)before=signature(pos(edit.cell()));
            if(NativeFixture.requested()&&pursuit)LegacyMod.logger.info("LEGACY_PURSUIT_PLAN from={} to={} edit={}",point(planned.from()),point(planned.to()),edit==null?"WALK":edit.kind()+":"+pos(edit.cell()));
        }
        if(edit==null){
            if(actor.getPositionVector().squareDistanceTo(point(planned.to()))<(pursuit?.0001:.16)&&actor.onGround){
                if(pursuit&&!approachSteps.isEmpty()){
                    planned=approachSteps.removeFirst();edit=planned.edits().isEmpty()?null:planned.edits().get(0);actionAt=tick;sent=jumped=false;settled=0;progress=0;
                    state=edit==null?"APPROACH_WALK":edit.kind()==Kind.BREAK?"APPROACH_MINE":"APPROACH_PLACE";if(edit!=null)before=signature(pos(edit.cell()));
                    if(NativeFixture.requested())LegacyMod.logger.info("LEGACY_PURSUIT_CONTINUE to={} edit={}",point(planned.to()),edit==null?"WALK":edit.kind()+":"+pos(edit.cell()));return null;
                }
                complete(changed);return null;
            }
            if(pursuit&&!planned.from().equals(planned.to())&&new LegacyTraversal(actor).transition(node(planned.from()),node(planned.to()))==null){fail("APPROACH_ROUTE_CHANGED");return null;}
            if(tick-actionAt>50){fail("ESCAPE_MOVE_BLOCKED");return null;}
            return new PathStep(node(planned.from()),node(planned.to()),planned.to().y()>planned.from().y()?Action.JUMP:Action.WALK,Posture.STANDING,1);
        }
        BlockPos at=pos(edit.cell());if(!loaded(at)){fail("WAITING_CHUNKS");return null;}
        if(!signature(at).equals(before)){
            boolean verified=edit.kind()==Kind.BREAK?actor.worldObj.isAirBlock(at):actor.worldObj.getBlockState(at).getBlock()==Blocks.wool;
            if(!verified){fail("TERRAIN_TARGET_CHANGED");return null;}
            if(edit.kind()==Kind.PLACE&&planned.jumpPlace()){
                if(!actor.onGround||Math.abs(actor.posY-at.getY()-1)>.12){if(tick-actionAt>35)fail("SUPPORT_STANDING_NOT_CONFIRMED");return null;}
                if(++settled<2)return null;
            }
            complete(changed);return null;
        }
        if(edit.kind()==Kind.BREAK){
            if(!mayBreak(at)){fail("TERRAIN_BREAK_REVOKED");return null;}select(toolSlot(at));
            Vec3 visible=visibleMiningPoint(at);if(visible==null){fail("MINING_TARGET_OCCLUDED");return null;}aim(visible);
            int duration=breakTicks(at);if(duration>240||tick-actionAt>Math.max(30,duration+30)){fail("MINING_NO_CONFIRMED_PROGRESS");return null;}
            actor.swingItem();progress+=actor.worldObj.getBlockState(at).getBlock().getPlayerRelativeBlockHardness(actor,actor.worldObj,at);
            actor.worldObj.sendBlockBreakProgress(actor.getEntityId(),at,Math.min(9,(int)(progress*10)));
            if(progress>=1&&!sent){sent=true;if(actor.theItemInWorldManager.tryHarvestBlock(at)&&actor.worldObj.isAirBlock(at))broken++;else fail("NATIVE_BREAK_REJECTED");}
            return null;
        }
        if(!mayPlace(at)){fail("TERRAIN_PLACE_REVOKED");return null;}int slot=materialSlot();if(slot<0){fail("BUILDING_MATERIAL_REQUIRED");return null;}select(slot);
        if(planned.jumpPlace()){
            if(!jumped){
                if(!actor.onGround)return null;
                if(Math.hypot(actor.posX-at.getX()-.5,actor.posZ-at.getZ()-.5)>.1)return new PathStep(node(planned.from()),node(planned.from()),Action.WALK,Posture.STANDING,1);
                if(!actor.worldObj.getCollidingBoundingBoxes(actor,actor.getEntityBoundingBox().addCoord(0,1.3,0)).isEmpty()){fail("JUMP_COLUMN_HEADROOM_BLOCKED");return null;}
                actor.requestJump();jumped=true;actionAt=tick;return null;
            }
            if(actor.posY<at.getY()+1.02){if(tick-actionAt>15)fail("JUMP_PLACE_WINDOW_MISSED");return null;}
        }
        if(!sent){
            MovingObjectPosition hit=placementHit(at);if(hit==null){fail("SUPPORT_FACE_NOT_REACHABLE");return null;}aim(hit.hitVec);
            ItemStack stack=actor.getHeldItem();int count=stack.stackSize;BlockPos anchor=hit.getBlockPos();
            boolean accepted=actor.theItemInWorldManager.activateBlockOrUseItem(actor,actor.worldObj,stack,anchor,hit.sideHit,(float)(hit.hitVec.xCoord-anchor.getX()),(float)(hit.hitVec.yCoord-anchor.getY()),(float)(hit.hitVec.zCoord-anchor.getZ()));
            int used=count-stack.stackSize;if(accepted&&used>0&&actor.worldObj.getBlockState(at).getBlock()==Blocks.wool){placed++;consumed+=used;}
            actor.swingItem();actor.inventoryContainer.detectAndSendChanges();sent=true;actionAt=tick;
        }
        if(tick-actionAt>25)fail("PLACEMENT_NOT_CONFIRMED_CHECK_WORLD");return null;
    }
    private void complete(boolean[] changed){cancel();state="RECHECK_ORIGINAL_ROUTE";changed[0]=true;}
    private boolean stableAttackStance(LegacyTraversal world,Vec3 at,EntityPlayerMP target){
        // Do not accept a zero-width ray along the shared corner of two blocks.
        // The executed body has inertia and cannot maintain a mathematical grid point exactly.
        AxisAlignedBB box=target.getEntityBoundingBox();
        for(double[] delta:new double[][]{{.04,0},{-.04,0},{0,.04},{0,-.04}}){
            Vec3 eye=at.addVector(delta[0],actor.getEyeHeight(),delta[1]);
            Vec3 hit=new Vec3(Math.max(box.minX+.001,Math.min(box.maxX-.001,eye.xCoord)),Math.max(box.minY+.001,Math.min(box.maxY-.001,eye.yCoord)),Math.max(box.minZ+.001,Math.min(box.maxZ-.001,eye.zCoord)));
            if(eye.squareDistanceTo(hit)>9||!world.rayClear(eye,hit))return false;
        }return true;
    }
    private void fail(String reason){if(NativeFixture.requested())LegacyMod.logger.info("LEGACY_RECOVERY_FAILED reason={} position={} edit={}",reason,actor.getPositionVector(),edit==null?"none":edit.kind()+":"+pos(edit.cell()));if(edit!=null)rejected.reject(edit.cell(),edit.kind(),context(pos(edit.cell()),edit.kind()));cancel();state=reason;}
    private void aim(Vec3 at){Vec3 delta=at.subtract(actor.getPositionEyes(1));actor.rotationYaw=(float)Math.toDegrees(Math.atan2(delta.zCoord,delta.xCoord))-90;actor.rotationYawHead=actor.rotationYaw;actor.rotationPitch=(float)-Math.toDegrees(Math.atan2(delta.yCoord,Math.hypot(delta.xCoord,delta.zCoord)));}
    private Vec3 visibleMiningPoint(BlockPos target){
        for(double y:new double[]{.5,.05,.95})for(double x:new double[]{.5,.05,.95})for(double z:new double[]{.5,.05,.95}){
            Vec3 at=new Vec3(target.getX()+x,target.getY()+y,target.getZ()+z);if(actor.getPositionEyes(1).squareDistanceTo(at)>4.5*4.5)continue;
            MovingObjectPosition hit=actor.worldObj.rayTraceBlocks(actor.getPositionEyes(1),at,false,true,false);if(hit!=null&&target.equals(hit.getBlockPos()))return at;
        }return null;
    }
    private MovingObjectPosition placementHit(BlockPos target){
        for(EnumFacing face:EnumFacing.values()){
            BlockPos anchor=target.offset(face.getOpposite());if(!loaded(anchor)||actor.worldObj.isAirBlock(anchor))continue;
            Vec3 point=new Vec3(anchor.getX()+.5+face.getFrontOffsetX()*.5,anchor.getY()+.5+face.getFrontOffsetY()*.5,anchor.getZ()+.5+face.getFrontOffsetZ()*.5);
            if(actor.getPositionEyes(1).squareDistanceTo(point)>4.5*4.5)continue;
            MovingObjectPosition hit=actor.worldObj.rayTraceBlocks(actor.getPositionEyes(1),point,false,true,false);
            if(hit!=null&&!anchor.equals(hit.getBlockPos()))continue;
            return new MovingObjectPosition(point,face,anchor);
        }return null;
    }
}
