package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.*;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Real mining and vanilla pickup, sharing normal navigation, reservations, combat and recovery. */
public final class GatherSkill {
    private static final List<java.util.function.BiPredicate<Item,BlockState>> ADAPTERS=new java.util.concurrent.CopyOnWriteArrayList<>();
    public static AutoCloseable registerSource(java.util.function.BiPredicate<Item,BlockState> adapter){Objects.requireNonNull(adapter);ADAPTERS.add(adapter);return ()->ADAPTERS.remove(adapter);}
    static boolean source(Item item,BlockState state){
        if(state.getBlock().asItem()==item)return true;
        if(item==Items.COBBLESTONE&&state.is(Blocks.STONE)||item==Items.COBBLED_DEEPSLATE&&state.is(Blocks.DEEPSLATE))return true;
        for(var adapter:ADAPTERS)if(adapter.test(item,state))return true;return false;
    }
    static void tick(SkillWork w){
        Item wanted=BuiltInRegistries.ITEM.getValue(Identifier.parse(w.session.spec().crop()));if(wanted==null||wanted==Items.AIR){w.pause("GATHER_ITEM_UNAVAILABLE");return;}
        w.session.add("gatherInventory",w.count(wanted)-w.session.count("gatherInventory"));
        if(w.player().hasInfiniteMaterials()){w.waitFor("GATHER_REQUIRES_SURVIVAL_DROPS",100);return;}
        if(!TerrainProvenance.get(w.runtime.server).available()){w.waitFor("GATHER_PROVENANCE_LOADING",20);return;}
        if(w.operation!=null){verify(w,wanted);return;}
        if(w.block==null){
            if(SkillLootCollector.tick(w))return;
            if(w.count(wanted)>=w.session.spec().limit()){w.session.add("verifiedGatherInventory",w.count(wanted));w.completed("GATHER_INVENTORY_READY");return;}
            if(!space(w.player(),wanted)){w.waitFor("INVENTORY_FULL",40);return;}
            select(w,wanted);return;
        }
        if(!allowed(w,w.block)||!source(wanted,w.player().level().getBlockState(w.block))){w.abandonTarget();return;}
        if(!w.runtime.reservations.reserve(w.reservation(w.block),w.token(),w.tick(),100)){w.abandonTarget();return;}
        if(!w.reach(w.block,InteractionTargetResolver.Kind.BLOCK))return;
        int slot=NativeTerrainPolicy.toolSlot(w.player(),w.block);if(slot!=w.player().getInventory().getSelectedSlot()){w.actor.select(w.token(),slot);return;}
        var state=w.player().level().getBlockState(w.block);
        if(state.requiresCorrectToolForDrops()&&!w.player().getMainHandItem().isCorrectToolForDrops(state)){w.waitFor("GATHER_CORRECT_TOOL_REQUIRED",100);return;}
        if(!allowed(w,w.block)||!source(wanted,state)){w.abandonTarget();return;}
        w.expectedState=BlockStateParser.serialize(state);w.beforeCount=w.count(wanted);w.actor.aim(w.token(),Vec3.atCenterOf(w.block));SkillLootCollector.before(w);
        w.prepare("GATHER_BREAK",Map.of("block",w.block.toShortString(),"before",w.expectedState,"item",w.session.spec().crop(),"inventoryBefore",Integer.toString(w.beforeCount)));
    }
    private static void select(SkillWork w,Item wanted){
        var area=w.session.spec().area();int scanned=0;
        while(scanned++<24&&w.runtime.scan()){
            if(w.session.cursor()>=area.volume()){w.session.cursor(0);w.session.add("gatherScans",1);w.waitFor("GATHER_WAITING_FOR_RESOURCES_OR_LOADED_AREA",100);return;}
            var at=area.cell(w.session.cursor());w.session.cursor(w.session.cursor()+1);var pos=BlockPos.containing(at.x(),at.y(),at.z());
            if(!NativeTerrainPolicy.loaded(w.player(),pos))continue;var state=w.player().level().getBlockState(pos);if(!source(wanted,state)||!allowed(w,pos))continue;
            if(w.gatherRejected.containsKey(pos)){if(w.gatherRejected.get(pos).equals(state))continue;w.gatherRejected.remove(pos);}
            if(!w.runtime.reservations.reserve(w.reservation(pos),w.token(),w.tick(),100)){w.session.add("reservationConflicts",1);continue;}
            w.block=pos.immutable();w.stand=null;w.search=null;w.session.phase("GATHER_APPROACH");return;
        }
    }
    private static boolean allowed(SkillWork w,BlockPos pos){
        var player=w.player();var level=player.level();if(!NativeTerrainPolicy.loaded(player,pos)||!player.mayInteract(level,pos)||level.getBlockEntity(pos)!=null)return false;
        var state=level.getBlockState(pos);if(state.isAir()||state.getDestroySpeed(level,pos)<0||!state.getFluidState().isEmpty()||TerrainProvenance.get(w.runtime.server).knownPlaced(level,pos))return false;
        // Explicit gathering still respects provenance and the configured policy for unknown terrain.
        if(!MineAgentRuntimeServices.config(w.runtime.server).flag("autonomy.terrainRecovery.allowUnknownNaturalMaterials",true))return false;
        if(dev.mineagent.runtime.neoforge.ui.ServerInteractionRules.denied(player,pos,null,"block_break",false))return false;
        for(var side:Direction.values()){var neighbor=pos.relative(side);if(!NativeTerrainPolicy.loaded(player,neighbor))return false;var other=level.getBlockState(neighbor);if(!other.getFluidState().isEmpty()||side==Direction.UP&&other.getBlock() instanceof FallingBlock)return false;}
        return true;
    }
    private static void verify(SkillWork w,Item wanted){
        if(!w.saved.isDone()||w.block==null)return;var state=w.player().level().getBlockState(w.block);
        if(w.nativeBreak&&(!BlockStateParser.serialize(state).equals(w.expectedState)||w.count(wanted)>w.beforeCount)){w.nativeBreak=false;w.session.add("gatheredBlocks",1);w.confirm("VERIFIED_BLOCK_BROKEN",Map.of("after",BlockStateParser.serialize(state),"inventoryAfter",Integer.toString(w.count(wanted))));SkillLootCollector.remember(w,Vec3.atCenterOf(w.block));w.loot.entrySet().removeIf(e->e.getValue().item()!=wanted);w.abandonTarget();return;}
        if(!w.executed){if(!allowed(w,w.block)||!BlockStateParser.serialize(state).equals(w.expectedState)){w.confirm("NOT_EXECUTED_TARGET_CHANGED",Map.of("after",BlockStateParser.serialize(state)));w.abandonTarget();return;}w.executed=true;w.startedTick=w.tick();w.session.phase("GATHER_MINING");}
        if(w.interruptedOperation){w.pause("GATHER_OUTCOME_REQUIRES_RECONCILIATION");return;}
        int expected=NativeTerrainPolicy.breakTicks(w.player(),w.block);
        if(w.tick()-w.startedTick>Math.min(2400,Math.max(80L,(long)expected*3+40))){w.pause("GATHER_OUTCOME_REQUIRES_RECONCILIATION");return;}
        try{w.actor.breakBlock(w.token(),w.operation,w.block);}catch(IllegalStateException refused){if(!"SKILL_MINING_REJECTED".equals(refused.getMessage()))throw refused;w.gatherRejected.put(w.block,state);while(w.gatherRejected.size()>128)w.gatherRejected.remove(w.gatherRejected.keySet().iterator().next());w.confirm("NOT_EXECUTED_MINING_REJECTED",Map.of("reason",refused.getMessage()));w.abandonTarget();w.waitFor("GATHER_BREAK_REJECTED_OR_BLOCKED",40);}
    }
    private static boolean space(net.minecraft.server.level.ServerPlayer player,Item item){for(int i=0;i<36;i++){var stack=player.getInventory().getItem(i);if(stack.isEmpty()||stack.is(item)&&stack.getCount()<stack.getMaxStackSize())return true;}return false;}
    private GatherSkill(){}
}
