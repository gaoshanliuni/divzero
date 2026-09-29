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
            if(!w.pickedDrops.contains(saved.id))w.session.add("lootSourceNoLongerAvailable",1);w.loot.remove(saved.id);w.actor.stop(w.token());w.lootRetries=0;w.blockedLootTerrain=null;return !w.loot.isEmpty();
        }
        if(!canFit(p,drop.getItem())){w.waitFor("INVENTORY_FULL",40);return true;}
        if(w.lootRetries>=3){long terrain=terrain(w,drop);if(w.blockedLootTerrain==null)w.blockedLootTerrain=terrain;else if(w.blockedLootTerrain!=terrain){w.lootRetries=0;w.blockedLootTerrain=null;}if(w.lootRetries>=3){w.waitFor("LOOT_ROUTE_REQUIRES_NEW_APPROACH",100);return true;}}
        var node=new NativeTraversalEvaluator(p).closest(drop.position());if(node==null){w.lootRetries++;w.waitFor("LOOT_NO_SAFE_STAND",60);return true;}
        w.session.phase("COLLECTING_DROPS");if(p.distanceToSqr(drop)<1.5){w.actor.stop(w.token());return true;}
        String outcome=w.actor.move(w.token(),NativeTraversalEvaluator.point(node));if(Set.of("UNREACHABLE","INTERACTION_BLOCKED").contains(outcome)){w.lootRetries++;w.waitFor("LOOT_ROUTE_BLOCKED",60);}return true;
    }
    private static boolean canFit(net.minecraft.server.level.ServerPlayer p,ItemStack item){for(int i=0;i<36;i++){var slot=p.getInventory().getItem(i);if(slot.isEmpty()||ItemStack.isSameItemSameComponents(slot,item)&&slot.getCount()<slot.getMaxStackSize())return true;}return false;}
    private static long terrain(SkillWork w,ItemEntity drop){
        var p=w.player();var a=p.blockPosition();var b=drop.blockPosition();long hash=31*a.asLong()+b.asLong();
        // A bounded observation of the blocked local corridor, not another navigation attempt.
        int minX=Math.max(a.getX()-12,Math.min(a.getX(),b.getX())-2),maxX=Math.min(a.getX()+12,Math.max(a.getX(),b.getX())+2);
        int minZ=Math.max(a.getZ()-12,Math.min(a.getZ(),b.getZ())-2),maxZ=Math.min(a.getZ()+12,Math.max(a.getZ(),b.getZ())+2);
        for(var pos:net.minecraft.core.BlockPos.betweenClosed(minX,a.getY()-2,minZ,maxX,a.getY()+3,maxZ))hash=31*hash+(p.level().hasChunkAt(pos)?p.level().getBlockState(pos).hashCode():-1);
        return hash;
    }
    private SkillLootCollector(){}
}
