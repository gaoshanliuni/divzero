package dev.mineagent.runtime.neoforge.skill;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Carries items to the recipient and uses native dropping. Completion requires actual recipient pickup. */
public final class HandoffSkill {
    private record Pending(SkillWork work,UUID operation,UUID recipient,Item item,int quantity){}
    private static final Map<UUID,Pending> DROPS=new HashMap<>();
    static void tick(SkillWork w){
        if(w.player().hasInfiniteMaterials()){w.waitFor("HANDOFF_REQUIRES_FINITE_INVENTORY",100);return;}
        var target=w.session.spec().target().equals("$owner")?w.runtime.server.getPlayerList().getPlayer(w.session.owner()):w.player().level().getEntity(UUID.fromString(w.session.spec().target()));if(!(target instanceof ServerPlayer recipient)||!recipient.isAlive()||recipient.level()!=w.player().level()){w.waitFor("HANDOFF_RECIPIENT_UNAVAILABLE",20);return;}
        if(recipient==w.player()){w.pause("HANDOFF_SELF_TARGET");return;}
        Item item=BuiltInRegistries.ITEM.getValue(Identifier.parse(w.session.spec().crop()));if(item==null||item==Items.AIR){w.pause("HANDOFF_ITEM_UNAVAILABLE");return;}
        if(w.operation!=null){verify(w,recipient,item);return;}
        long remaining=w.session.spec().limit()-w.session.count("handoffReceived");if(remaining<=0){w.completed("HANDOFF_RECEIVED_BY_TARGET");return;}
        if(w.count(item)==0){w.waitFor("HANDOFF_MATERIALS_REQUIRED",40);return;}
        Vec3 delta=w.player().position().subtract(recipient.position());if(delta.horizontalDistanceSqr()<.01)delta=new Vec3(1,0,0);Vec3 at=recipient.position().add(delta.normalize().scale(1.5));
        if(w.player().distanceToSqr(recipient)>4||Math.abs(w.player().getY()-recipient.getY())>1){var node=new dev.mineagent.runtime.neoforge.body.NativeTraversalEvaluator(w.player()).closest(at);if(node==null){w.waitFor("HANDOFF_NO_SAFE_STAND",40);return;}w.session.phase("HANDOFF_APPROACH");w.move(dev.mineagent.runtime.neoforge.body.NativeTraversalEvaluator.point(node));return;}
        w.actor.haltMotion(w.token());if(!w.equip(item))return;w.actor.aimImmediately(w.token(),recipient.position().add(0,.5,0));
        int amount=w.player().getMainHandItem().getCount()<=remaining?w.player().getMainHandItem().getCount():1;w.beforeCount=w.count(item);w.handoffEntity=null;w.handoffQuantity=amount;w.handoffPicked=0;w.handoffDiverted=0;
        w.prepare("HANDOFF_DROP",Map.of("recipient",recipient.getUUID().toString(),"item",w.session.spec().crop(),"quantity",Integer.toString(amount),"inventoryBefore",Integer.toString(w.beforeCount)));
    }
    private static void verify(SkillWork w,ServerPlayer recipient,Item item){
        if(!w.saved.isDone())return;
        if(w.handoffDiverted>0){w.pause("HANDOFF_ITEMS_PICKED_BY_OTHER_PLAYER");return;}
        if(w.handoffPicked>=w.handoffQuantity&&w.handoffQuantity>0&&w.count(item)<=w.beforeCount-w.handoffQuantity){
            w.session.add("handoffReceived",w.handoffQuantity);var evidence=Map.of("recipient",recipient.getUUID().toString(),"received",Integer.toString(w.handoffQuantity),"dropEntity",Objects.toString(w.handoffEntity,""));if(w.handoffEntity!=null)DROPS.remove(w.handoffEntity);w.confirm("VERIFIED_RECIPIENT_PICKUP",evidence);w.handoffEntity=null;return;
        }
        if(w.interruptedOperation){w.pause("HANDOFF_PENDING_RECEIPT_RECONCILE");return;}
        if(!w.executed){
            if(w.player().distanceToSqr(recipient)>6.25||!w.player().getMainHandItem().is(item)||w.count(item)!=w.beforeCount){w.confirm("NOT_EXECUTED_TARGET_OR_ITEMS_CHANGED",Map.of());return;}
            w.executed=true;w.startedTick=w.tick();w.session.phase("HANDOFF_WAITING_PICKUP");
        }
        if(w.handoffEntity==null&&w.tick()-w.startedTick<60)w.actor.dropItem(w.token(),w.operation,w.player().getMainHandItem().getCount()==w.handoffQuantity);
        if(w.tick()-w.startedTick>240){w.pause("HANDOFF_PENDING_RECEIPT_RECONCILE");return;}
        // Repeated client frames retain the same operation ID, so no extra Q action is issued.
        if(w.handoffEntity!=null&&w.count(item)>w.beforeCount-w.handoffQuantity){if(w.tick()-w.startedTick>40)w.pause("HANDOFF_SOURCE_COUNT_REQUIRES_RECONCILIATION");return;}
        if(w.handoffEntity!=null&&w.player().level().getEntity(w.handoffEntity) instanceof ItemEntity drop){
            // Keep the sender clear so it does not reclaim the offered items.
            if(w.player().distanceToSqr(drop)<1.5){var away=w.player().position().subtract(drop.position());if(away.horizontalDistanceSqr()>.01){var node=new dev.mineagent.runtime.neoforge.body.NativeTraversalEvaluator(w.player()).closest(w.player().position().add(away.normalize()));if(node!=null)w.actor.move(w.token(),dev.mineagent.runtime.neoforge.body.NativeTraversalEvaluator.point(node));}}
        }
    }
    static void tossed(SkillWork w,ItemEntity entity){
        if(w.operation==null||!w.executed||!w.action.equals("HANDOFF_DROP")||w.handoffEntity!=null||!BuiltInRegistries.ITEM.getKey(entity.getItem().getItem()).toString().equals(w.session.spec().crop())||entity.getItem().getCount()!=w.handoffQuantity)return;
        w.handoffEntity=entity.getUUID();DROPS.put(entity.getUUID(),new Pending(w,w.operation,UUID.fromString(w.session.receipt().get("recipient")),entity.getItem().getItem(),w.handoffQuantity));
        entity.getPersistentData().putString("divzero_handoff_session",w.token().toString());entity.getPersistentData().putString("divzero_handoff_operation",w.operation.toString());
        var receipt=new LinkedHashMap<>(w.session.receipt());receipt.put("dropEntity",entity.getUUID().toString());w.session.receipt(receipt);w.runtime.persist(w);
    }
    static void pickedUp(ServerPlayer player,ItemEntity entity,ItemStack before,ItemStack after){
        var pending=DROPS.get(entity.getUUID());if(pending==null)return;var w=pending.work();if(w.actor==null||w.player().level()!=player.level()||!Objects.equals(w.operation,pending.operation())||!before.is(pending.item()))return;
        int count=before.getCount()-after.getCount();if(count<=0)return;int credit=Math.min(count,Math.max(0,pending.quantity()-w.handoffPicked-w.handoffDiverted));if(player.getUUID().equals(pending.recipient()))w.handoffPicked+=credit;else w.handoffDiverted+=credit;
        var receipt=new LinkedHashMap<>(w.session.receipt());receipt.put("received",Integer.toString(w.handoffPicked));receipt.put("diverted",Integer.toString(w.handoffDiverted));receipt.put("observedRecipient",player.getUUID().toString());w.session.receipt(receipt);w.runtime.persist(w);if(after.isEmpty())DROPS.remove(entity.getUUID());
    }
    static void stopped(net.minecraft.server.MinecraftServer server){DROPS.entrySet().removeIf(e->e.getValue().work().runtime.server==server);}
    private HandoffSkill(){}
}
