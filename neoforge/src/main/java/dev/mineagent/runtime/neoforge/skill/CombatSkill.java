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
        if(w.combatStage==0&&w.combatOperation!=null&&(w.player().getAttackStrengthScale(.5f)<.8f||w.lastHitAt>=w.combatAt)){w.lastAttackAt=w.tick();log(w,"NATIVE_ATTACK_OBSERVED");w.combatOperation=null;}
        observeRelease(w);observeFood(w);w.combat.scan(w);
        var spec=w.session.spec();var rule=spec.combat();
        if(rule.engagement()==CombatPolicy.Engagement.NONE&&rule.strategy()!=CombatPolicy.Strategy.DISENGAGE){finishDefense(w);return spec.kind()==SkillSpec.Kind.COMBAT&&idleCombat(w,"ENGAGEMENT_DISABLED");}
        LivingEntity target=w.combat.selected;
        if(spec.kind()==SkillSpec.Kind.COMBAT&&!rule.target().isBlank()){
            var requested=w.player().level().getEntity(UUID.fromString(rule.target()));
            if(requested instanceof LivingEntity e&&!e.isAlive()||requested==null&&w.lastCombatTarget!=null&&!w.lastCombatTarget.isAlive()){
                w.completed("TARGET_CONFIRMED_DEAD");return true;
            }
        }
        boolean projectileDanger=w.combat.projectiles.stream().anyMatch(e->e.distanceTo(w.player())<10);
        boolean cooling=w.combatInterrupted&&w.tick()-w.combat.lastThreatTick<40;
        boolean closeThreat=w.combat.threats.stream().anyMatch(t->t.urgent()&&t.entity().distanceTo(w.player())<8);
        if(target==null&&!projectileDanger&&!cooling&&!closeThreat){
            if(w.combatInterrupted&&w.player().getHealth()<w.player().getMaxHealth()*.7&&w.player().getFoodData().getFoodLevel()<20&&safeToEat(w)&&w.acquire()&&eat(w))return true;
            finishDefense(w);return spec.kind()==SkillSpec.Kind.COMBAT&&idleCombat(w,"NO_ELIGIBLE_THREATS");
        }
        if(!w.combatInterrupted){
            w.combatInterrupted=true;w.suspendedPhase=w.session.phase();
            w.interruptedOperation=w.operation!=null;
            if(w.operation!=null&&!w.executed)w.confirm("NOT_EXECUTED_INTERRUPTED",Map.of("interruptedBy","DEFENSE"));
            if(spec.kind()==SkillSpec.Kind.FISH)FishSkill.interrupt(w);
            w.actor.stop(w.token());w.actor.controls().release(w.token());w.runtime.reservations.release(w.token());
            w.combatStage=0;w.combatOperation=null;w.positioning.reset();w.session.add("defenseInterruptions",1);
            if(spec.kind()!=SkillSpec.Kind.COMBAT)w.notice("defending","发现威胁，先防御，之后继续原工作。");
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
        w.session.transition(State.RUNNING,"DEFENSE_FINISHED_RECHECK_WORK");w.session.add("workResumptions",1);w.nextTick=w.tick();w.runtime.persist(w);if(w.session.spec().kind()!=SkillSpec.Kind.COMBAT)w.notice("resumed","威胁已解除，重新检查并继续原工作。");
    }
    static void combat(SkillWork w){interruptOrContinue(w);}
    static void policyChanged(SkillWork w){var state=w.session.state();var reason=w.session.reason();finishDefense(w);if(state==State.PAUSED)w.session.transition(state,reason);w.combat.selected=null;w.combat.nextScan=0;w.lastCombatTick=-1;w.positioning.reset();}
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
        if(escape&&w.positioning.longRetreat()&&w.player().isUsingItem()&&!w.player().getUseItem().getOrDefault(DataComponents.USE_EFFECTS,net.minecraft.world.item.component.UseEffects.DEFAULT).canSprint()){w.actor.stop(w.token());w.combatStage=0;w.combatOperation=null;w.shieldOperation=null;w.healingOperation=null;}
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
        if(health<.7&&p.getFoodData().getFoodLevel()<20&&safeToEat(w)){
            if(w.healingOperation==null)w.actor.stop(w.token());
            if(eat(w))return;
        }
        double withdrawal=Math.max(4,p.getAttackRangeWith(p.getMainHandItem()).effectiveMaxRange(p)+1+contacts*.65);
        boolean retreat=rule.strategy()==CombatPolicy.Strategy.DISENGAGE||health<.3||flanked||contacts>1;
        if(retreat||target==null){
            phase(w,health<.3?"RECOVER":flanked||contacts>1?"LURE":"RETREAT");
            var facing=target==null?w.combat.threats.stream().map(CombatAwareness.Threat::entity).min(Comparator.comparingDouble(p::distanceToSqr)).orElse(null):target;
            move(w,w.positioning.choose(w,w.tactic,health<.3?14:withdrawal+2),facing,true);
            if(!w.positioning.longRetreat())shield(w,facing);return;
        }
        if(CombatEquipmentAdapter.execute(w,target)){phase(w,"ADAPTED_WEAPON");return;}
        double distance=p.distanceTo(target);boolean visible=p.hasLineOfSight(target);
        var actual=NativeCombatStates.read(target,p);
        boolean haveBow=w.count(Items.BOW)>0&&(w.count(Items.ARROW)>0||p.hasInfiniteMaterials());
        boolean meleeAvailable=false;for(int slot=0;slot<36;slot++){var held=p.getInventory().getItem(slot);if(held.is(ItemTags.SWORDS)||held.is(ItemTags.AXES)){meleeAvailable=true;break;}}
        boolean stalled=w.lastAttackAt>=0&&w.tick()-w.lastAttackAt>120;
        boolean ranged=rule.strategy()==CombatPolicy.Strategy.RANGED_KITE||rule.strategy()==CombatPolicy.Strategy.AUTO&&haveBow&&(distance>5||!meleeAvailable&&distance>3||stalled&&distance>4);
        if(ranged&&haveBow){
            double desired=Math.max(7,Math.min(12,withdrawal+4));
            if(distance<desired-1||!visible)move(w,w.positioning.choose(w,distance<desired?"RETREAT":"RANGED",desired),target,distance<desired-2);
            else if(distance>desired+3)move(w,w.positioning.choose(w,"APPROACH",desired),target,false);
            else if(w.combatStage==0)w.actor.stop(w.token());
            if(visible&&distance<24&&distance>=Math.max(4,desired-2)){bow(w,target);return;}
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
            if(rule.strategy()==CombatPolicy.Strategy.HOLD_POSITION){w.actor.aim(w.token(),target.getEyePosition());w.actor.haltMotion(w.token());shield(w,target);return;}
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
        if(r<1){move(w,w.positioning.choose(w,"RANGED",8),target,false);return;}
        var aim=new Vec3(point.x,p.getEyeY()+(v2-Math.sqrt(disc))/(g*Math.max(.01,r))*r,point.z);w.actor.aim(w.token(),aim);
        if(friendlyInArc(w,target,aim)){w.actor.stop(w.token());w.combatStage=0;w.combatOperation=null;phase(w,"FRIENDLY_LINE_OF_FIRE");move(w,w.positioning.choose(w,"LURE",10),target,false);return;}
        if(w.combatStage==0){w.combatOperation=UUID.randomUUID();w.combatAt=w.tick();w.combatStage=1;w.shotLogged=false;w.combatAmmo=w.count(Items.ARROW);w.session.add("bowAttempts",1);}
        if(w.combatStage==1){phase(w,"BOW_DRAW");w.actor.useHand(w.token(),w.combatOperation,InteractionHand.MAIN_HAND);if(p.isUsingItem()&&p.getTicksUsingItem()>=20){w.combatStage=2;w.combatAt=w.tick();}else if(w.tick()-w.combatAt>100){w.actor.stop(w.token());w.combatStage=0;}return;}
        phase(w,"BOW_RELEASE");w.actor.releaseItem(w.token(),w.combatOperation);observeRelease(w);
        if(!p.isUsingItem()&&w.tick()-w.combatAt>8){w.lastAttackAt=w.tick();w.combatStage=0;w.combatOperation=null;}
    }
    private static boolean friendlyInArc(SkillWork w,LivingEntity target,Vec3 aim){
        var p=w.player();var start=p.getEyePosition();double r=Math.hypot(aim.x-start.x,aim.z-start.z),slope=(aim.y-start.y)/Math.max(.01,r),duration=r*Math.sqrt(1+slope*slope)/3;
        var allies=p.level().getEntitiesOfClass(LivingEntity.class,p.getBoundingBox().minmax(target.getBoundingBox()).inflate(2),e->e!=p&&e!=target&&e.isAlive()&&(e instanceof net.minecraft.world.entity.player.Player||e instanceof OwnableEntity own&&own.getOwnerReference()!=null||e.isAlliedTo(p)||!(e instanceof net.minecraft.world.entity.monster.Enemy)));
        for(int i=0;i<=20;i++){double t=i/20.0;var sample=new Vec3(start.x+(aim.x-start.x)*t,start.y+slope*r*t-.025*duration*duration*t*t,start.z+(aim.z-start.z)*t);for(var ally:allies)if(ally.getBoundingBox().inflate(.5).contains(sample))return true;}return false;
    }
    private static boolean shield(SkillWork w,LivingEntity target){
        var p=w.player();if(target==null)return false;
        if(!p.getOffhandItem().is(Items.SHIELD))for(int i=0;i<36;i++)if(p.getInventory().getItem(i).is(Items.SHIELD)){w.actor.equipOffhand(w.token(),i);return true;}
        if(!p.getOffhandItem().is(Items.SHIELD)||p.getCooldowns().isOnCooldown(p.getOffhandItem()))return false;
        if(w.combatStage!=0){observeRelease(w);w.actor.stop(w.token());w.combatStage=0;w.combatOperation=null;w.shieldOperation=null;}
        // A shield protects only its facing direction; do not block while turning to sprint away.
        if(p.getLookAngle().dot(target.position().subtract(p.position()).normalize())<.3)return false;
        w.actor.aim(w.token(),target.getEyePosition());
        if(w.shieldOperation==null||w.wasBlocking&&!p.isUsingItem()){w.shieldOperation=UUID.randomUUID();w.wasBlocking=false;}
        w.actor.useHand(w.token(),w.shieldOperation,InteractionHand.OFF_HAND);w.wasBlocking|=p.isUsingItem()&&p.getUsedItemHand()==InteractionHand.OFF_HAND;return true;
    }
    private static boolean eat(SkillWork w){
        var p=w.player();if(p.getFoodData().getFoodLevel()>=20)return false;
        for(int i=0;i<36;i++){var stack=p.getInventory().getItem(i);if(food(w,stack)){
            if(!w.equip(stack.getItem()))return true;
            if(w.healSlot!=i||w.healingOperation==null){w.healSlot=i;w.healingOperation=UUID.randomUUID();}
            if(!w.healingWasUsing)w.foodBefore=p.getFoodData().getFoodLevel();w.actor.useHand(w.token(),w.healingOperation,InteractionHand.MAIN_HAND);w.healingWasUsing|=p.isUsingItem();phase(w,"EATING_IN_SAFE_SPACE");return true;
        }}return false;
    }
    private static boolean safeToEat(SkillWork w){
        var p=w.player();int remaining=40;
        if(w.healingOperation!=null&&p.isUsingItem()&&p.getUsedItemHand()==InteractionHand.MAIN_HAND&&p.getUseItem().has(DataComponents.FOOD))remaining=p.getUseItemRemainingTicks();
        else for(int i=0;i<36;i++){var stack=p.getInventory().getItem(i);if(food(w,stack)){remaining=stack.getUseDuration(p);break;}}
        final int untilFinished=Math.max(0,remaining)+8;
        return w.combat.risk(w,p.position())<2&&w.combat.threats.stream().allMatch(t->t.entity().distanceTo(p)>3+untilFinished*Math.max(t.state().velocity().horizontalDistance(),t.state().movementSpeed()));
    }
    private static boolean food(SkillWork w,ItemStack stack){return stack.has(DataComponents.FOOD)&&!stack.is(Items.ROTTEN_FLESH)&&!stack.is(Items.SPIDER_EYE)&&!stack.is(Items.PUFFERFISH)&&!stack.is(Items.POISONOUS_POTATO)&&!(w.session.spec().kind()==SkillSpec.Kind.FARM&&CropAdapter.adapters().stream().anyMatch(a->stack.is(a.seed()))&&w.count(stack.getItem())<=1);}
    private static void observeFood(SkillWork w){if(w.healingWasUsing&&!w.player().isUsingItem()){if(w.player().getFoodData().getFoodLevel()>w.foodBefore)w.session.add("nativeFoodConsumptions",1);w.healingWasUsing=false;w.healingOperation=null;}}
    private CombatSkill(){}
}
