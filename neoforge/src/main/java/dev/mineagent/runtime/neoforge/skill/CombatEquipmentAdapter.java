package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.core.task.CombatPolicy;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Trusted Java extensions use the same actor lease; they must preserve native consumption and report observations. */
public interface CombatEquipmentAdapter {
    record Context(SkillActor actor,UUID session,UUID operation,LivingEntity target,
                   NativeCombatStates.Snapshot observed,CombatPolicy policy,boolean safeToEngage){}
    String id();
    /** Positive scores opt in. Unadapted items continue through the standard native controller. */
    int score(ItemStack stack,CombatPolicy policy);
    boolean tick(Context context);
    List<CombatEquipmentAdapter> REGISTRY=new CopyOnWriteArrayList<>();
    static void register(CombatEquipmentAdapter adapter){Objects.requireNonNull(adapter);if(REGISTRY.stream().anyMatch(a->a.id().equals(adapter.id())))throw new IllegalArgumentException("COMBAT_ADAPTER_DUPLICATE");REGISTRY.add(adapter);}
    static boolean hasMeleeWeapon(SkillWork w){
        var p=w.player();for(int i=0;i<36;i++){var stack=p.getInventory().getItem(i);if(stack.isEmpty()||stack.isDamageableItem()&&stack.getMaxDamage()-stack.getDamageValue()<2)continue;
            var attrs=stack.getOrDefault(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS,net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY);
            if(stack.is(net.minecraft.tags.ItemTags.SWORDS)||stack.is(net.minecraft.tags.ItemTags.AXES)||attrs.compute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE,p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE).getBaseValue(),net.minecraft.world.entity.EquipmentSlot.MAINHAND)>2)return true;
        }return false;
    }
    static boolean melee(SkillWork w,LivingEntity target){
        var player=w.player();boolean blocking=target!=null&&target.isBlocking()&&target.getLookAngle().dot(player.position().subtract(target.position()).normalize())>.15;
        if(w.weaponPendingSlot>=0){
            if(ItemStack.isSameItemSameComponents(player.getMainHandItem(),w.weaponPendingStack)){w.weaponPendingSlot=-1;w.weaponPendingStack=ItemStack.EMPTY;}
            else if(ItemStack.isSameItemSameComponents(player.getInventory().getItem(w.weaponPendingSlot),w.weaponPendingStack))return finishSelection(w);
            else{w.weaponPendingSlot=-1;w.weaponPendingStack=ItemStack.EMPTY;w.weaponDecisionTick=-10000;}
        }
        boolean falling=NativeAttackReadiness.preparingFall(player,target);
        if(w.weaponDecisionTick>w.tick()-8&&w.weaponBlocking==blocking&&w.weaponFalling==falling)return true;
        w.weaponFalling=falling;
        w.weaponDecisionTick=w.tick();w.weaponBlocking=blocking;
        double best=Double.NEGATIVE_INFINITY;int selected=-1;
        double currentSpeed=Math.max(.1,player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED));
        double elapsed=player.getAttackStrengthScale(0)*20/currentSpeed;
        for(int slot=0;slot<36;slot++){
            var stack=player.getInventory().getItem(slot);if(stack.isEmpty())continue;
            var attributes=stack.getOrDefault(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS,net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY);
            double damage=attributes.compute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE,player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE).getBaseValue(),net.minecraft.world.entity.EquipmentSlot.MAINHAND);
            double speed=Math.max(.1,attributes.compute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED,player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED).getBaseValue(),net.minecraft.world.entity.EquipmentSlot.MAINHAND));
            if(target!=null)damage+=Math.max(0,stack.getItem().getAttackDamageBonus(target,(float)damage,player.damageSources().playerAttack(player)));
            boolean axe=stack.is(net.minecraft.tags.ItemTags.AXES);
            if(!axe&&!stack.is(net.minecraft.tags.ItemTags.SWORDS)&&damage<=2)continue;
            if(stack.isDamageableItem()&&stack.getMaxDamage()-stack.getDamageValue()<2)continue;
            double score=damage*(falling?1.6:.5+Math.min(3,speed)*.5)-Math.max(0,20/speed-elapsed)*.15;
            if(falling&&stack.getItem() instanceof net.minecraft.world.item.MaceItem)score+=20;
            if(blocking)score+=axe?40:-10;
            if(slot==player.getInventory().getSelectedSlot())score+=.4;
            if(stack.isDamageableItem()&&stack.getMaxDamage()-stack.getDamageValue()<10)score-=4;
            if(score>best){best=score;selected=slot;}
        }
        if(selected<0||selected==player.getInventory().getSelectedSlot())return true;
        w.weaponPendingSlot=selected;w.weaponPendingStack=player.getInventory().getItem(selected).copy();
        w.session.add(blocking?"shieldCounterWeaponSelections":"meleeWeaponSelections",1);return finishSelection(w);
    }
    private static boolean finishSelection(SkillWork w){
        var player=w.player();
        if(NativeEquipmentSupport.protects(player.getMainHandItem())&&!NativeEquipmentSupport.protects(player.getOffhandItem())&&!w.actor.equipOffhand(w.token(),player.getInventory().getSelectedSlot()))return false;
        if(player.isUsingItem()){w.actor.stop(w.token());w.shieldOperation=null;w.healingOperation=null;}
        boolean selected=w.actor.select(w.token(),w.weaponPendingSlot);
        if(selected&&ItemStack.isSameItemSameComponents(player.getMainHandItem(),w.weaponPendingStack)){w.weaponPendingSlot=-1;w.weaponPendingStack=ItemStack.EMPTY;return true;}return false;
    }
    static boolean execute(SkillWork w,LivingEntity target){
        CombatEquipmentAdapter best=null;int score=0,slot=-1;
        for(var adapter:REGISTRY)for(int i=0;i<36;i++){int value=adapter.score(w.player().getInventory().getItem(i),w.session.spec().combat());if(value>score){score=value;slot=i;best=adapter;}}
        if(best==null)return false;if(!w.actor.select(w.token(),slot))return true;
        if(w.extensionOperation==null)w.extensionOperation=UUID.randomUUID();
        return best.tick(new Context(w.actor,w.token(),w.extensionOperation,target,NativeCombatStates.read(target,w.player()),w.session.spec().combat(),!w.combat.flanked(w)&&w.combat.contacts(w)<=1));
    }
}
