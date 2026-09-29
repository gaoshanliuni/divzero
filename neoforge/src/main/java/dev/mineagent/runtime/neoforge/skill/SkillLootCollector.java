package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.neoforge.body.NativeTraversalEvaluator;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.*;
import java.util.*;

/** Walk to observed drops and let vanilla pickup run. Never grants or teleports an item. */
final class SkillLootCollector {
    record Drop(UUID id,Item item,Vec3 position,int countBefore){}
    static void before(SkillWork w){
        w.lootBefore.clear();for(int i=0;i<36;i++){var stack=w.player().getInventory().getItem(i);if(!stack.isEmpty())w.lootBefore.merge(stack.getItem(),stack.getCount(),Integer::sum);}
        Vec3 center=w.block==null?w.player().position():Vec3.atCenterOf(w.block);
        w.dropBefore=w.player().level().getEntitiesOfClass(ItemEntity.class,new AABB(center,center).inflate(8)).stream().map(ItemEntity::getUUID).collect(java.util.stream.Collectors.toSet());
    }
    static void remember(SkillWork w,Vec3 center){
        for(var e:w.player().level().getEntitiesOfClass(ItemEntity.class,new AABB(center,center).inflate(8)))if(!w.dropBefore.contains(e.getUUID())&&!w.loot.containsKey(e.getUUID()))w.loot.put(e.getUUID(),new Drop(e.getUUID(),e.getItem().getItem(),e.position(),w.count(e.getItem().getItem())));
        var receipt=new LinkedHashMap<>(w.session.receipt());receipt.put("pendingLoot",w.loot.keySet().toString());w.session.receipt(receipt);w.runtime.persist(w);
    }
    static boolean tick(SkillWork w){
        if(w.loot.isEmpty())return false;
        var saved=w.loot.values().iterator().next();var p=w.player();if(!p.level().hasChunkAt(net.minecraft.core.BlockPos.containing(saved.position))){w.waitFor("LOOT_CHUNK_UNLOADED",40);return true;}
        var entity=p.level().getEntity(saved.id);int count=w.count(saved.item);
        if(!(entity instanceof ItemEntity drop)||!drop.isAlive()){
            if(!w.pickedDrops.contains(saved.id))w.session.add("lootSourceNoLongerAvailable",1);w.loot.remove(saved.id);w.actor.stop(w.token());w.lootRetries=0;return !w.loot.isEmpty();
        }
        if(!canFit(p,drop.getItem())){w.waitFor("INVENTORY_FULL",40);return true;}
        if(w.lootRetries>=3){w.waitFor("LOOT_ROUTE_REQUIRES_NEW_APPROACH",100);return true;}
        var node=new NativeTraversalEvaluator(p).closest(drop.position());if(node==null){w.lootRetries++;w.waitFor("LOOT_NO_SAFE_STAND",60);return true;}
        w.session.phase("COLLECTING_DROPS");if(p.distanceToSqr(drop)<1.5){w.actor.stop(w.token());return true;}
        String outcome=w.actor.move(w.token(),NativeTraversalEvaluator.point(node));if(Set.of("UNREACHABLE","INTERACTION_BLOCKED").contains(outcome)){w.lootRetries++;w.waitFor("LOOT_ROUTE_BLOCKED",60);}return true;
    }
    private static boolean canFit(net.minecraft.server.level.ServerPlayer p,ItemStack item){for(int i=0;i<36;i++){var slot=p.getInventory().getItem(i);if(slot.isEmpty()||ItemStack.isSameItemSameComponents(slot,item)&&slot.getCount()<slot.getMaxStackSize())return true;}return false;}
    private SkillLootCollector(){}
}
