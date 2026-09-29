package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.neoforge.body.InteractionTargetResolver;
import dev.mineagent.runtime.neoforge.mixin.SkillFishingHookAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import java.util.*;

final class FishSkill {
    static void tick(SkillWork w){
        var p=w.player();if(w.count(Items.FISHING_ROD)<1){w.waitFor("FISHING_ROD_MISSING",40);return;}if(!w.inventorySpace()){w.waitFor("INVENTORY_FULL",40);return;}
        if(w.interruptedOperation||w.hook!=null&&p.fishing!=null&&!w.hook.equals(p.fishing.getUUID())){w.pause("FISHING_HOOK_CHANGED_RECONCILE");return;}
        if(w.block==null){search(w);return;}
        if(!p.level().hasChunkAt(w.block)||!p.level().getFluidState(w.block).is(FluidTags.WATER)){w.abandonTarget();w.waitFor("FISHING_SPOT_CHANGED",40);return;}
        if(w.workStage==0){
            if(w.operation!=null){
                if(!w.saved.isDone())return;w.actor.aim(w.token(),Vec3.atCenterOf(w.block));if(p.fishing==null&&w.workStage==0||p.fishing!=null&&w.workStage==2)w.actor.useItem(w.token(),w.operation,false);w.executed=true;
                if(p.fishing!=null){w.hook=p.fishing.getUUID();w.confirm("CAST_CONFIRMED",Map.of("hook",w.hook.toString()));w.workStage=1;w.session.phase("FISH_WAIT_BITE");w.startedTick=w.tick();}
                else if(w.tick()-w.startedTick>80)w.pause("FISH_CAST_OUTCOME_UNCERTAIN");return;
            }
            if(p.fishing!=null){w.pause("EXISTING_HOOK_NOT_OWNED_BY_SKILL");return;}
            if(!w.reach(w.block,InteractionTargetResolver.Kind.FISH)||!w.equip(Items.FISHING_ROD))return;w.actor.aim(w.token(),Vec3.atCenterOf(w.block));w.beforeDamage=p.getMainHandItem().getDamageValue();
            w.prepare("FISH_CAST",Map.of("rodDamage",Integer.toString(w.beforeDamage),"water",w.block.toShortString()));return;
        }
        if(w.workStage==1){
            var hook=p.fishing;if(hook==null||hook.isRemoved()){w.pause("OWNED_HOOK_DISAPPEARED");return;}
            if(hook.getPlayerOwner()!=p||!hook.getUUID().equals(w.hook)){w.pause("FISH_HOOK_OWNERSHIP_CHANGED");return;}
            if(hook instanceof SkillFishingHookAccess bite&&bite.divzero$skillNibble()>0){w.workStage=2;w.fishedEvent=false;w.fishedItems=0;w.fishStatBefore=p.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.FISH_CAUGHT));w.fishInventoryBefore=inventory(p);w.nearbyItemsBefore=p.level().getEntitiesOfClass(ItemEntity.class,p.getBoundingBox().inflate(8)).stream().map(ItemEntity::getUUID).collect(java.util.stream.Collectors.toSet());w.prepare("FISH_REEL",Map.of("hook",w.hook.toString(),"rodDamage",Integer.toString(p.getMainHandItem().getDamageValue())));return;}
            w.session.transition(dev.mineagent.runtime.core.task.SkillSession.State.WAITING,"WAITING_FOR_REAL_BITE");if(w.actor instanceof PlayerSkillActor adapter&&w.tick()%20==0)adapter.report("WAITING_FOR_REAL_BITE",false);if(w.tick()-w.startedTick>7200)w.pause("FISHING_NO_BITE_REQUIRES_NEW_SPOT");return;
        }
        if(w.workStage==2){
            if(!w.saved.isDone())return;w.actor.aim(w.token(),Vec3.atCenterOf(w.block));if(p.fishing==null&&w.workStage==0||p.fishing!=null&&w.workStage==2)w.actor.useItem(w.token(),w.operation,false);w.executed=true;
            if(p.fishing==null){int fishStat=p.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.FISH_CAUGHT));var after=inventory(p);int newItems=p.level().getEntitiesOfClass(ItemEntity.class,p.getBoundingBox().inflate(8),e->!w.nearbyItemsBefore.contains(e.getUUID())).stream().mapToInt(e->e.getItem().getCount()).sum();boolean pickup=!after.equals(w.fishInventoryBefore);
                if(w.fishedEvent&&(fishStat>w.fishStatBefore||newItems>0||pickup)){w.session.add("fishingCatches",1);w.session.add("nativeFishingDrops",w.fishedItems);if(pickup)w.session.add("fishingPickupObserved",1);w.confirm("REEL_VERIFIED",Map.of("hook",w.hook.toString(),"rodDamageAfter",Integer.toString(p.getMainHandItem().getDamageValue()),"pickupObserved",Boolean.toString(pickup)));w.hook=null;w.workStage=0;
                    if(!w.session.spec().repeat()||w.session.spec().limit()>0&&w.session.count("fishingCatches")>=w.session.spec().limit()){w.completed("FISHING_TARGET_MET");return;}w.waitFor("NEXT_CAST",20);return;}
            }
            if(w.tick()-w.startedTick>100)w.pause("FISH_REEL_OUTCOME_UNCERTAIN");
        }
    }
    private static Map<String,Integer> inventory(net.minecraft.server.level.ServerPlayer p){var result=new TreeMap<String,Integer>();for(int i=0;i<36;i++){var s=p.getInventory().getItem(i);if(!s.isEmpty())result.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString(),s.getCount(),Integer::sum);}return result;}
    private static void search(SkillWork w){var area=w.session.spec().area();for(int i=0;i<16&&w.runtime.scan();i++){
        if(w.session.cursor()>=area.volume()){w.session.cursor(0);w.waitFor("NO_LOADED_FISHING_WATER",80);return;}var n=area.cell(w.session.cursor());w.session.cursor(w.session.cursor()+1);var p=BlockPos.containing(n.x(),n.y(),n.z());if(!w.player().level().hasChunkAt(p))continue;if(w.player().level().getFluidState(p).is(FluidTags.WATER)&&w.player().level().getFluidState(p.above()).isEmpty()){w.block=p;w.stand=null;w.search=null;w.workStage=0;return;}
    }}
    private FishSkill(){}
}
