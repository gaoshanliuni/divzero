package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mineagent.runtime.core.task.ReusablePathPlan;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.*;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Native, incremental bridge/stair construction. Pillars include permanent ladders for the owner. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class NativeAgentPathBuilder {
 private static final Map<MinecraftServer,List<Job>> JOBS=new IdentityHashMap<>();
 private static ReusablePathPlan.Cell cell(JsonNode n){if(!n.isArray()||n.size()!=3)throw new IllegalArgumentException("PATH_COORDINATES");for(var v:n)if(!v.isIntegralNumber()||!v.canConvertToInt())throw new IllegalArgumentException("PATH_COORDINATES");return new ReusablePathPlan.Cell(n.get(0).intValue(),n.get(1).intValue(),n.get(2).intValue());}
 private static BlockPos pos(ReusablePathPlan.Cell c){return new BlockPos(c.x(),c.y(),c.z());}
 public static CompletableFuture<Map<String,Object>> start(ServerPlayer p,UUID agent,UUID op,JsonNode a,BooleanSupplier permit){
  if(!ServerTaskStart.allowed(p,agent))throw new SecurityException("AGENT_PATH_PERMISSION");var b=MineAgentRuntimeServices.bodies(p.level().getServer()).body(agent).orElseThrow();
  if(!b.canAct()||b.taskControlOwned()||b.level()!=p.level()||b.isPassenger())throw new IllegalStateException("AGENT_PATH_BUSY_OR_DIMENSION");
  var from=a.has("from")?cell(a.get("from")):new ReusablePathPlan.Cell(b.blockPosition().getX(),(int)Math.round(b.getY()),b.blockPosition().getZ());var plan=ReusablePathPlan.between(from,cell(a.path("to")));
  var id=net.minecraft.resources.Identifier.tryParse(a.path("item").asText("minecraft:cobblestone"));if(id==null||!net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(id))throw new IllegalArgumentException("PATH_BLOCK_UNKNOWN");var block=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(id);
  if(!(block.asItem() instanceof BlockItem)||block instanceof FallingBlock||!block.defaultBlockState().isCollisionShapeFullBlock(b.level(),pos(from)))throw new IllegalArgumentException("PATH_REQUIRES_STABLE_FULL_BLOCK");
  var job=new Job(p,b,op,plan,block,permit);if(!b.claimTaskControl(op,job::valid,()->job.finish("CANCELLED","CONTROL_REVOKED")))throw new IllegalStateException("AGENT_PATH_BUSY");b.movementController().stop();JOBS.computeIfAbsent(p.level().getServer(),k->new ArrayList<>()).add(job);return job.result;
 }
 public static boolean stop(ServerPlayer p,UUID agent){for(var j:List.copyOf(JOBS.getOrDefault(p.level().getServer(),List.of())))if(j.owner==p&&j.body.agentId().equals(agent)){j.finish("CANCELLED","USER_STOPPED");return true;}return false;}
 private static final class Job {
  final ServerPlayer owner;final MineAgentPlayer body;final UUID operation;final ReusablePathPlan plan;final Block block;final BooleanSupplier permit;final net.minecraft.server.level.ServerLevel level;final CompletableFuture<Map<String,Object>> result=new CompletableFuture<>();
  int index,phase,at,placed,ladders,jumps,walkSteps;boolean done;long navigation;List<BlockPos> supports=List.of();int supportIndex;
  Job(ServerPlayer p,MineAgentPlayer b,UUID op,ReusablePathPlan plan,Block block,BooleanSupplier permit){owner=p;body=b;operation=op;this.plan=plan;this.block=block;this.permit=permit;level=b.level();at=level.getServer().getTickCount();}
  boolean valid(){return !done&&permit.getAsBoolean()&&body.canAct()&&body.level()==level&&owner.level()==level&&level.getServer().getPlayerList().getPlayer(owner.getUUID())==owner&&MineAgentRuntimeServices.bodies(level.getServer()).body(body.agentId()).orElse(null)==body&&ServerTaskStart.allowed(owner,body.agentId());}
  void local(BlockPos p){if(!level.getChunkSource().hasChunk(p.getX()>>4,p.getZ()>>4)||level.isOutsideBuildHeight(p)||!level.getWorldBorder().isWithinBounds(p))throw new IllegalStateException("PATH_UNLOADED_OR_OUTSIDE_WORLD");}
  void equip(Block block){var stack=new ItemStack(block.asItem());int slot=body.getInventory().findSlotMatchingItem(stack);if(slot>=0){if(slot<9)body.getInventory().setSelectedSlot(slot);else body.getInventory().pickSlot(slot);}else if(body.hasInfiniteMaterials())body.setItemInHand(InteractionHand.MAIN_HAND,stack);else throw new IllegalStateException("PATH_MATERIAL_MISSING:"+net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()));body.inventoryMenu.broadcastChanges();}
  void place(BlockPos target,Block wanted){local(target);var state=level.getBlockState(target);if(state.is(wanted))return;if(!state.isAir())throw new IllegalStateException("PATH_OCCUPIED_NO_OVERWRITE:"+target.toShortString());if(ServerInteractionRules.denied(body,target,null,"block_place",true))throw new IllegalStateException("PATH_PLACEMENT_RULE_DENIED");equip(wanted);body.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,Vec3.atCenterOf(target));if(!MineAgentRuntimeServices.bodies(level.getServer()).placeBlock(body.agentId(),target,wanted))throw new IllegalStateException("PATH_NATIVE_PLACE_REJECTED:"+target.toShortString());if(wanted==Blocks.LADDER)ladders++;else placed++;}
  void go(Vec3 target){body.movementController().setSneaking(body,false);body.movementController().movePreciselyTo(target);navigation=body.movementController().commandRevision();}
  void tick(){if(done)return;try{if(!valid())throw new IllegalStateException("PATH_CONTEXT_CHANGED");int tick=level.getServer().getTickCount();if(tick-at>600)throw new IllegalStateException("PATH_STEP_NO_PROGRESS");
   var current=pos(plan.feet().get(index));local(current);local(current.above());
   if(phase==0){if(!level.getBlockState(current.below()).isCollisionShapeFullBlock(level,current.below()))throw new IllegalStateException("PATH_START_REQUIRES_FULL_FLOOR");go(Vec3.atBottomCenterOf(current));phase=1;return;}
   if(phase==1||phase==5){if(body.movementController().target().isPresent())return;if(!body.movementController().outcome().equals("ARRIVED"))throw new IllegalStateException("PATH_WALK_"+body.movementController().outcome());walkSteps+=body.movementController().executedSteps();if(phase==5){index++;at=tick;}if(index==plan.feet().size()-1){finish("APPLIED","");return;}phase=2;}
   current=pos(plan.feet().get(index));var next=pos(plan.feet().get(index+1));local(next);local(next.above());if(phase==2){
    if(!NativeSurfaceNavigation.clearPose(body,next.getX()+.5,next.getY(),next.getZ()+.5,net.minecraft.world.entity.Pose.STANDING))throw new IllegalStateException("PATH_HEADROOM_BLOCKED");
    if(plan.pillar()){if(!body.onGround())return;if(index==0){var landing=current.below().east();local(landing);if(!level.getBlockState(landing).isCollisionShapeFullBlock(level,landing))place(landing,block);}if(!body.hasInfiniteMaterials()&&body.getInventory().findSlotMatchingItem(new ItemStack(Items.LADDER))<0)throw new IllegalStateException("PATH_LADDER_MISSING");body.movementController().setSneaking(body,false);equip(block);body.jumpFromGround();jumps++;phase=3;return;}
    var list=new ArrayList<BlockPos>();int y0=current.getY()-1,y1=next.getY()-1;
    if(y1<y0)list.add(current.below(2));for(int y=Math.min(y0,y1);y<=y1;y++)list.add(new BlockPos(next.getX(),y,next.getZ()));supports=List.copyOf(list);supportIndex=0;phase=4;
   }
   if(phase==3){if(body.getY()<next.getY()+.02){if(body.onGround())throw new IllegalStateException("PATH_JUMP_NO_CLEARANCE");return;}place(next.below(),block);phase=6;return;}
   if(phase==6){if(!body.onGround())return;if(Math.abs(body.getY()-next.getY())>.15)throw new IllegalStateException("PATH_PILLAR_LANDING_FAILED"); // Ladder makes a vertical tower reusable, not just an AI-only jump.
    place(next.below().east(),Blocks.LADDER);index++;at=tick;if(index==plan.feet().size()-1){finish("APPLIED","");return;}phase=2;return;}
   if(phase==4){if(supportIndex<supports.size()){var support=supports.get(supportIndex++);local(support);if(!level.getBlockState(support).isCollisionShapeFullBlock(level,support))place(support,block);return;}go(Vec3.atBottomCenterOf(next));phase=5;}
  }catch(Exception e){finish(placed+ladders>0?"PARTIAL":"REJECTED",Objects.toString(e.getMessage(),"PATH_FAILED"));}}
  void finish(String status,String error){if(done)return;done=true;if(navigation>0)body.movementController().stopIfCurrent(navigation);body.movementController().setSneaking(body,false);body.releaseTaskControl(operation);var out=new LinkedHashMap<String,Object>();out.put("status",status);out.put("error",error);out.put("from",plan.feet().getFirst());out.put("to",plan.feet().getLast());out.put("completedSegments",index);out.put("segments",plan.feet().size()-1);out.put("blocksPlaced",placed);out.put("laddersPlaced",ladders);out.put("nativeJumps",jumps);out.put("nativeWalkSteps",walkSteps);out.put("position",List.of(body.getX(),body.getY(),body.getZ()));out.put("permanent",true);out.put("reusable",status.equals("APPLIED"));out.put("kind",plan.pillar()?"PILLAR_WITH_LADDER":"BRIDGE_OR_STAIRS");out.put("note","Placed blocks remain in the world on completion or stop. Existing terrain is never cleared. A partial route is not a completed bridge. Survival consumes AI inventory; pillar also needs ladders. One-block-wide route, no automatic rails.");result.complete(out);}
 }
 @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e){var list=JOBS.get(e.getServer());if(list!=null)for(var j:List.copyOf(list)){j.tick();if(j.done)list.remove(j);}}
 @SubscribeEvent public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent e){var list=JOBS.remove(e.getServer());if(list!=null)for(var j:list)j.finish("CANCELLED","SERVER_STOPPED");}
 private NativeAgentPathBuilder(){}
}
