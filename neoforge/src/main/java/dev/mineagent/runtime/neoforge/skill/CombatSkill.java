package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.core.task.*;
import dev.mineagent.runtime.core.task.SkillSession.State;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Immediate local tactics using live threats, native reach, cooldowns and observed outcomes. */
final class CombatSkill {
    static boolean interruptOrContinue(SkillWork w){
        if(w.lastCombatTick==w.tick())return w.lastCombatResult;
        w.lastCombatTick=w.tick();w.lastCombatResult=advance(w);return w.lastCombatResult;
    }
    private static boolean advance(SkillWork w){
        observeRelease(w);w.combat.scan(w);
        var spec=w.session.spec();var rule=spec.combat();
        if(rule.engagement()==CombatPolicy.Engagement.NONE){finishDefense(w);return spec.kind()==SkillSpec.Kind.COMBAT&&idleCombat(w,"ENGAGEMENT_DISABLED");}
        LivingEntity target=w.combat.selected;
        if(spec.kind()==SkillSpec.Kind.COMBAT&&!rule.target().isBlank()){
            var requested=w.player().level().getEntity(UUID.fromString(rule.target()));
            if(requested instanceof LivingEntity e&&!e.isAlive()||requested==null&&w.lastCombatTarget!=null&&!w.lastCombatTarget.isAlive()){
                w.completed("TARGET_CONFIRMED_DEAD");return true;
            }
        }
        boolean projectileDanger=w.combat.projectiles.stream().anyMatch(e->e.distanceTo(w.player())<10);
        boolean cooling=w.combatInterrupted&&w.tick()-w.combat.lastThreatTick<40;
        if(target==null&&!projectileDanger&&!cooling){
            finishDefense(w);return spec.kind()==SkillSpec.Kind.COMBAT&&idleCombat(w,"NO_ELIGIBLE_THREATS");
        }
        if(!w.combatInterrupted){
            w.combatInterrupted=true;w.suspendedPhase=w.session.phase();
            w.interruptedOperation=w.operation!=null;
            if(w.operation!=null&&!w.executed)w.confirm("NOT_EXECUTED_INTERRUPTED",Map.of("interruptedBy","DEFENSE"));
            if(spec.kind()==SkillSpec.Kind.FISH)FishSkill.interrupt(w);
            w.actor.stop(w.token());w.actor.controls().release(w.token());w.runtime.reservations.release(w.token());
            w.combatStage=0;w.combatOperation=null;w.positioning.reset();w.session.add("defenseInterruptions",1);
        }
        w.fighting=target==null?null:target.getUUID();
        if(!w.acquire())return true;
        w.session.transition(State.SUSPENDED,target==null?"THREAT_CLEARANCE_HYSTERESIS":"DEFENDING_THEN_RESUME");
        if(target!=null)w.lastCombatTarget=target;
        fight(w,target);return true;
    }
    private static boolean idleCombat(SkillWork w,String reason){w.waitFor(reason,10);return true;}
    private static void finishDefense(SkillWork w){
        if(!w.combatInterrupted)return;
        w.actor.stop(w.token());w.actor.controls().release(w.token());w.combatInterrupted=false;w.fighting=null;w.combatOperation=null;w.combatStage=0;w.healingOperation=null;
        w.session.phase(w.suspendedPhase==null?"SCAN":w.suspendedPhase);w.stand=null;w.search=null;w.positioning.reset();
        w.session.transition(State.RUNNING,"DEFENSE_FINISHED_RECHECK_WORK");w.session.add("workResumptions",1);w.nextTick=w.tick();w.runtime.persist(w);
    }
    static void combat(SkillWork w){interruptOrContinue(w);}
    static void policyChanged(SkillWork w){finishDefense(w);w.combat.selected=null;w.combat.nextScan=0;w.lastCombatTick=-1;w.positioning.reset();}
    private static void phase(SkillWork w,String name){if(!w.tactic.equals(name)){w.tactic=name;w.tacticAt=w.tick();w.session.add("tactic_"+name,1);}w.session.phase(name);if(w.actor instanceof PlayerSkillActor p&&w.tick()%10==0)p.report(name,false);}
    private static void log(SkillWork w,String state){
        var receipt=new LinkedHashMap<>(w.session.receipt());receipt.put("combatState",state);receipt.put("combatOperation",w.combatOperation==null?"":w.combatOperation.toString());receipt.put("combatActorEntityId",Integer.toString(w.player().getId()));receipt.put("combatIntentRevision",Long.toString(w.session.revision()));receipt.put("combatTarget",w.fighting==null?"":w.fighting.toString());receipt.put("arrowsAfter",Integer.toString(w.count(Items.ARROW)));w.session.receipt(receipt);
        // Persistence is asynchronous; tactics and urgent defense never wait for this journal.
        w.runtime.persist(w);
    }
    private static void observeRelease(SkillWork w){
        if(w.combatStage==2&&!w.shotLogged&&w.count(Items.ARROW)<w.combatAmmo){w.shotLogged=true;w.session.add("arrowsReleased",1);log(w,"RELEASE_OBSERVED");}
    }
    private static void move(SkillWork w,Vec3 next,LivingEntity target,boolean escape){
        if(next==null){phase(w,w.positioning.pending()?"WAITING_FOR_TACTICAL_PATH":"NO_SAFE_EXIT");shield(w,target);return;}
        w.actor.sprint(w.token(),escape&&w.positioning.longRetreat());
        if(target!=null&&(!escape||!w.positioning.longRetreat()))w.actor.aim(w.token(),target.getEyePosition());
        else w.actor.aim(w.token(),next.add(0,w.player().getEyeHeight(),0));
        w.actor.move(w.token(),next);
    }
    private static void fight(SkillWork w,LivingEntity target){
        var p=w.player();var rule=w.session.spec().combat();
        if(target!=null&&(target instanceof net.minecraft.world.entity.player.Player||target.isAlliedTo(p)||target instanceof OwnableEntity owned&&owned.getOwnerReference()!=null)){w.combat.selected=null;return;}
        int contacts=w.combat.contacts(w);boolean flanked=w.combat.flanked(w);
        double health=p.getHealth()/Math.max(1,p.getMaxHealth());
        double withdrawal=Math.max(4,p.getAttackRangeWith(p.getMainHandItem()).effectiveMaxRange(p)+1+contacts*.65);
        boolean retreat=rule.strategy()==CombatPolicy.Strategy.DISENGAGE||health<.3||flanked||contacts>1;
        if(retreat||target==null){
            phase(w,health<.3?"RECOVER":flanked||contacts>1?"LURE":"RETREAT");
            move(w,w.positioning.choose(w,w.tactic,health<.3?14:withdrawal+2),target,true);
            if(health<.7&&w.combat.risk(w,p.position())<2&&w.combat.threats.stream().allMatch(t->t.entity().distanceTo(p)>3+40*Math.max(t.state().velocity().horizontalDistance(),t.state().movementSpeed()))&&eat(w))return;
            shield(w,target);return;
        }
        double distance=p.distanceTo(target);boolean visible=p.hasLineOfSight(target);
        var actual=NativeCombatStates.read(target,p);
        boolean haveBow=w.count(Items.BOW)>0&&(w.count(Items.ARROW)>0||p.hasInfiniteMaterials());
        boolean ranged=rule.strategy()==CombatPolicy.Strategy.RANGED_KITE||rule.strategy()==CombatPolicy.Strategy.AUTO&&haveBow&&(distance>5||w.combat.threats.size()>1||w.tick()-w.lastAttackAt>120);
        if(ranged&&haveBow){
            double desired=Math.max(7,Math.min(12,withdrawal+4));
            if(distance<desired-1||!visible)move(w,w.positioning.choose(w,distance<desired?"RETREAT":"RANGED",desired),target,distance<desired-2);
            else if(distance>desired+3)move(w,w.positioning.choose(w,"APPROACH",desired),target,false);
            else if(w.combatStage==0)w.actor.stop(w.token());
            if(visible&&distance<24){bow(w,target);return;}
            phase(w,"RANGED_REPOSITION");return;
        }
        if(w.combatStage!=0){w.actor.stop(w.token());w.combatStage=0;w.combatOperation=null;}
        double reach=p.getAttackRangeWith(p.getMainHandItem()).effectiveMaxRange(p);
        boolean ready=p.getAttackStrengthScale(.5f)>=.95f;
        boolean inReach=p.isWithinAttackRange(p.getMainHandItem(),target.getHitbox(),0)&&visible;
        boolean attackedRecently=w.tick()-w.lastAttackAt<8||!ready;
        boolean contactDanger=actual.inNativeMeleeRange()&&!actual.meleeRestricted()&&actual.attacks().stream().anyMatch(a->a.running()&&a.kind().equals("MELEE")&&(a.cooldownTicks()<0||a.cooldownTicks()<5));
        if(rule.strategy()!=CombatPolicy.Strategy.HOLD_POSITION&&(attackedRecently||contactDanger||actual.areaAttack())){
            phase(w,"MELEE_EXIT");
            move(w,w.positioning.choose(w,"RETREAT",withdrawal),target,false);
            shield(w,target);return;
        }
        if(!inReach){
            phase(w,"MELEE_APPROACH");
            if(rule.strategy()==CombatPolicy.Strategy.HOLD_POSITION){w.actor.stop(w.token());shield(w,target);return;}
            move(w,w.positioning.choose(w,"APPROACH",Math.max(1,reach-.6)),target,false);return;
        }
        if(!ready){phase(w,"COOLDOWN_GUARD");shield(w,target);return;}
        Vec3 exit=w.positioning.choose(w,"RETREAT",withdrawal);
        if(exit==null&&rule.strategy()!=CombatPolicy.Strategy.HOLD_POSITION){phase(w,"NO_SAFE_EXIT");shield(w,target);return;}
        if(!weapon(w))return;
        if(p.isUsingItem())p.stopUsingItem();
        w.actor.aim(w.token(),target.getEyePosition());
        if(w.combatOperation==null||w.tick()-w.combatAt>5){w.combatOperation=UUID.randomUUID();w.combatAt=w.tick();w.session.add("meleeAttempts",1);}
        w.actor.attack(w.token(),w.combatOperation,target);
        // Client attacks are acknowledged by native cooldown/damage; never assume a hit or knockback.
        if(p.getAttackStrengthScale(.5f)<.8f||w.lastHitAt>=w.combatAt){
            w.lastAttackAt=w.tick();log(w,"NATIVE_ATTACK_OBSERVED");w.combatOperation=null;
            phase(w,"MELEE_EXIT");if(rule.strategy()!=CombatPolicy.Strategy.HOLD_POSITION)move(w,exit,target,false);
        }
    }
    private static boolean weapon(SkillWork w){
        var p=w.player();if(p.getMainHandItem().is(ItemTags.SWORDS)||p.getMainHandItem().is(ItemTags.AXES))return true;
        for(int i=0;i<36;i++){var item=p.getInventory().getItem(i);if(item.is(ItemTags.SWORDS)||item.is(ItemTags.AXES))return w.actor.select(w.token(),i);}
        return true;
    }
    private static void bow(SkillWork w,LivingEntity target){
        if(!w.equip(Items.BOW))return;
        var p=w.player();var point=target.getEyePosition();double r=Math.hypot(point.x-p.getX(),point.z-p.getZ()),dy=point.y-p.getEyeY(),v2=9,g=.05,disc=v2*v2-g*(g*r*r+2*dy*v2);
        if(disc<0)return;
        var aim=new Vec3(point.x,p.getEyeY()+(v2-Math.sqrt(disc))/(g*Math.max(.01,r))*r,point.z);w.actor.aim(w.token(),aim);
        if(w.combatStage==0){w.combatOperation=UUID.randomUUID();w.combatAt=w.tick();w.combatStage=1;w.shotLogged=false;w.combatAmmo=w.count(Items.ARROW);w.session.add("bowAttempts",1);}
        if(w.combatStage==1){phase(w,"BOW_DRAW");w.actor.useHand(w.token(),w.combatOperation,InteractionHand.MAIN_HAND);if(p.isUsingItem()&&p.getTicksUsingItem()>=20){w.combatStage=2;w.combatAt=w.tick();}else if(w.tick()-w.combatAt>100){w.actor.stop(w.token());w.combatStage=0;}return;}
        phase(w,"BOW_RELEASE");w.actor.releaseItem(w.token(),w.combatOperation);observeRelease(w);
        if(!p.isUsingItem()&&w.tick()-w.combatAt>8){w.lastAttackAt=w.tick();w.combatStage=0;w.combatOperation=null;}
    }
    private static boolean shield(SkillWork w,LivingEntity target){
        var p=w.player();if(target==null)return false;
        if(!p.getOffhandItem().is(Items.SHIELD))for(int i=0;i<36;i++)if(p.getInventory().getItem(i).is(Items.SHIELD)){w.actor.equipOffhand(w.token(),i);return true;}
        if(!p.getOffhandItem().is(Items.SHIELD)||p.getCooldowns().isOnCooldown(p.getOffhandItem()))return false;
        // A shield protects only its facing direction; do not block while turning to sprint away.
        if(p.getLookAngle().dot(target.position().subtract(p.position()).normalize())<.3)return false;
        w.actor.aim(w.token(),target.getEyePosition());
        if(w.healingOperation==null)w.healingOperation=UUID.randomUUID();
        w.actor.useHand(w.token(),w.healingOperation,InteractionHand.OFF_HAND);return true;
    }
    private static boolean eat(SkillWork w){
        var p=w.player();if(w.healingWasUsing&&!p.isUsingItem()){if(p.getFoodData().getFoodLevel()>w.foodBefore)w.session.add("nativeFoodConsumptions",1);w.healingWasUsing=false;w.healingOperation=null;}if(p.getFoodData().getFoodLevel()>=20)return false;
        for(int i=0;i<36;i++){var stack=p.getInventory().getItem(i);if(stack.has(DataComponents.FOOD)&&!stack.is(Items.ROTTEN_FLESH)&&!stack.is(Items.SPIDER_EYE)&&!stack.is(Items.PUFFERFISH)&&!stack.is(Items.POISONOUS_POTATO)){
            if(!w.equip(stack.getItem()))return true;
            if(w.healSlot!=i||w.healingOperation==null){w.healSlot=i;w.healingOperation=UUID.randomUUID();}
            if(!w.healingWasUsing)w.foodBefore=p.getFoodData().getFoodLevel();w.actor.useHand(w.token(),w.healingOperation,InteractionHand.MAIN_HAND);w.healingWasUsing|=p.isUsingItem();phase(w,"EATING_IN_SAFE_SPACE");return true;
        }}return false;
    }
    private CombatSkill(){}
}