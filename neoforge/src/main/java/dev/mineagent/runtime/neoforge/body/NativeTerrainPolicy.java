package dev.mineagent.runtime.neoforge.body;

import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.task.AutonomousPlayerAgent;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import java.util.*;

/** Server-owned local terrain rules, checked again immediately before each native action. */
public final class NativeTerrainPolicy {
    private static final Set<Block> TERRAIN=Set.of(Blocks.DIRT,Blocks.GRASS_BLOCK,Blocks.COARSE_DIRT,Blocks.ROOTED_DIRT,Blocks.PODZOL,Blocks.MYCELIUM,
            Blocks.STONE,Blocks.ANDESITE,Blocks.DIORITE,Blocks.GRANITE,Blocks.DEEPSLATE,Blocks.TUFF,Blocks.NETHERRACK,Blocks.END_STONE,Blocks.CLAY,Blocks.MUD);
    public static boolean allowed(ServerPlayer player){
        if(!dev.mineagent.runtime.neoforge.skill.ActorEnhancements.forBody(player).recovery()||!player.isAlive()||player.isSpectator()||player.isPassenger())return false;
        var server=player.level().getServer();if(!MineAgentRuntimeServices.config(server).flag("autonomy.terrainRecovery.enabled",true))return false;
        if(player instanceof MineAgentPlayer body){var owner=server.getPlayerList().getPlayer(body.ownerPlayerId());return owner!=null&&body.canAct()&&body.taskControlOwned()&&ServerTaskStart.allowed(owner,body.agentId());}
        return AutonomousPlayerAgent.emergencySession(player)!=null;
    }
    public static boolean loaded(ServerPlayer p,BlockPos at){return p.level().hasChunkAt(at)&&!p.level().isOutsideBuildHeight(at)&&p.level().getWorldBorder().isWithinBounds(at);}
    public static boolean mayBreak(ServerPlayer p,BlockPos at){
        if(!allowed(p)||!loaded(p,at)||p.level().getBlockEntity(at)!=null||!p.mayInteract(p.level(),at))return false;
        var state=p.level().getBlockState(at);if(dev.mineagent.runtime.neoforge.skill.PvpMapSupport.enabled())return dev.mineagent.runtime.neoforge.skill.NativeHumanDuel.woolAction(p,at)&&state.is(net.minecraft.tags.BlockTags.WOOL)&&!dev.mineagent.runtime.neoforge.ui.ServerInteractionRules.denied(p,at,null,"block_break",false);var history=TerrainProvenance.get(p.level().getServer());
        if(!history.available()||history.knownPlaced(p.level(),at)||!TERRAIN.contains(state.getBlock())||!state.getFluidState().isEmpty())return false;
        if(!MineAgentRuntimeServices.config(p.level().getServer()).flag("autonomy.terrainRecovery.allowUnknownNaturalMaterials",true))return false;
        if(dev.mineagent.runtime.neoforge.ui.ServerInteractionRules.denied(p,at,null,"block_break",false))return false;
        // Do not open a local pocket into fluid, falling blocks, a container or an unloaded neighbour.
        for(var direction:net.minecraft.core.Direction.values()){var neighbor=at.relative(direction);if(!loaded(p,neighbor))return false;var other=p.level().getBlockState(neighbor);if(!other.getFluidState().isEmpty()||direction==net.minecraft.core.Direction.UP&&other.getBlock() instanceof FallingBlock)return false;}
        return true;
    }
    public static boolean mayPlace(ServerPlayer p,BlockPos at){return allowed(p)&&loaded(p,at)&&(!dev.mineagent.runtime.neoforge.skill.PvpMapSupport.enabled()||dev.mineagent.runtime.neoforge.skill.NativeHumanDuel.woolAction(p,at))&&p.mayInteract(p.level(),at)&&p.level().getBlockState(at).isAir()&&p.level().getFluidState(at).isEmpty()&&!dev.mineagent.runtime.neoforge.ui.ServerInteractionRules.denied(p,at,null,"block_place",false);}
    public static int materialSlot(ServerPlayer p){
        for(int i=0;i<36;i++){var stack=p.getInventory().getItem(i);if(dev.mineagent.runtime.neoforge.skill.PvpMapSupport.enabled()){if(stack.is(net.minecraft.tags.ItemTags.WOOL))return i;continue;}if(stack.getItem() instanceof BlockItem item&&(TERRAIN.contains(item.getBlock())||item.getBlock()==Blocks.COBBLESTONE||item.getBlock()==Blocks.COBBLED_DEEPSLATE||stack.is(net.minecraft.tags.ItemTags.PLANKS))&&!(item.getBlock() instanceof FallingBlock)&&item.getBlock().defaultBlockState().isCollisionShapeFullBlock(p.level(),p.blockPosition())&&!item.getBlock().defaultBlockState().hasBlockEntity()&&item.getBlock()!=Blocks.MAGMA_BLOCK)return i;}return -1;
    }
    public static int materialCount(ServerPlayer p){int slot=materialSlot(p);return slot<0?0:p.hasInfiniteMaterials()?64:p.getInventory().getItem(slot).getCount();}
    public static int breakTicks(ServerPlayer p,BlockPos at){float progress=p.level().getBlockState(at).getDestroyProgress(p,p.level(),at);return progress<=0||!Float.isFinite(progress)?Integer.MAX_VALUE:Math.max(1,(int)Math.ceil(1/progress));}
    public static int toolSlot(ServerPlayer p,BlockPos at){var state=p.level().getBlockState(at);int best=p.getInventory().getSelectedSlot();float speed=p.getMainHandItem().getDestroySpeed(state);for(int i=0;i<36;i++){var stack=p.getInventory().getItem(i);if(stack.getDestroySpeed(state)>speed&&(!stack.isDamageableItem()||stack.getDamageValue()<stack.getMaxDamage()-1)){best=i;speed=stack.getDestroySpeed(state);}}return best;}
    private NativeTerrainPolicy(){}
}
