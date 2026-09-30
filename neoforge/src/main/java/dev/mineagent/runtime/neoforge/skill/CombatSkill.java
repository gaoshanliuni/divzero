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
        if(w.legacyBaseline())return LegacyCombatBaseline.interruptOrContinue(w);
        if(w.lastCombatTick==w.tick())return w.lastCombatResult;
        w.lastCombatTick=w.tick();w.lastCombatResult=advance(w);return w.lastCombatResult;
    }
    private static boolean advance(SkillWork w){
        if(w.lastCombatTarget!=null&&!w.lastCombatTarget.isAlive()&&w.creditedDeath!=w.lastCombatTarget&&w.lastCombatTarget.getKillCredit()==w.player()){w.creditedDeath=w.lastCombatTarget;w.session.add("verifiedKills",1);}
        if(w.lastTacticalJump>w.observedTacticalJump&&w.tick()-w.lastTacticalJump<=8&&w.player().getY()>w.tacticalJumpY+.2){w.observedTacticalJump=w.lastTacticalJump;w.session.add(w.jumpKind.equals("JUMP_TAP")?"observedJumpTaps":"observedCounterJumps",1);if(w.jumpKind.equals("JUMP_TAP")&&!w.player().isSprinting())w.session.add("jumpTapSprintResets",1);}
        if(w.combatStage==0&&w.combatOperation!=null&&(w.player().getAttackStrengthScale(.5f)<.8f||w.lastHitAt>=w.combatAt)){w.lastAttackAt=w.tick();log(w,"NATIVE_ATTACK_OBSERVED");w.combatOperation=null;}
        observeRelease(w);observeFood(w);w.combat.scan(w);observeFootwork(w);
        if(w.shieldCounterTarget instanceof net.minecraft.world.entity.player.Player shielded&&w.tick()-w.shieldCounterAt<=40&&!shielded.isBlocking()&&shielded.getCooldowns().isOnCooldown(w.shieldCounterItem)){w.session.add("confirmedShieldDisables",1);w.shieldCounterTarget=null;}

        if(w.combat.contacts(w)>0){if(w.contactSince<0)w.contactSince=w.tick();}else w.contactSince=-1;
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
        if(target==null&&!projectileDanger&&!cooling&&!closeThreat&&!w.contactEscape){
            if(w.combatInterrupted&&w.player().getHealth()<w.player().getMaxHealth()*.7&&w.player().getFoodData().getFoodLevel()<20&&safeToEat(w)&&w.acquire()&&eat(w))return true;
            var lastSeen=w.combat.lastSeenSearch(w);
            if(lastSeen!=null&&w.acquire()){
                // A guard checks remembered coordinates; it does not read a hidden target's new position.
                phase(w,"REACQUIRE_THREAT");w.session.transition(State.SUSPENDED,"CHECKING_LAST_THREAT_POSITION");w.actor.sprint(w.token(),false);w.actor.aim(w.token(),lastSeen.add(0,w.player().getEyeHeight(),0));
                String navigation=w.actor.move(w.token(),lastSeen);if(Set.of("NO_PATH","FAILED","ARRIVED","CANCELLED").contains(navigation))w.combat.searchFailed();return true;
            }
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
        w.actor.stop(w.token());w.actor.controls().release(w.token());w.combatInterrupted=false;w.fighting=null;w.combatOperation=null;w.combatStage=0;w.healingOperation=null;w.contactEscape=false;w.contactRunAndHit=false;w.contactSince=w.contactClearSince=-1;
        w.session.phase(w.suspendedPhase==null?"SCAN":w.suspendedPhase);w.stand=null;w.search=null;w.positioning.reset();w.footwork.reset();w.jumpTapUntil=w.sideStepUntil=-1;
        w.session.transition(State.RUNNING,"DEFENSE_FINISHED_RECHECK_WORK");w.session.add("workResumptions",1);w.nextTick=w.tick();w.runtime.persist(w);if(w.session.spec().kind()!=SkillSpec.Kind.COMBAT)w.notice("resumed","威胁已解除，重新检查并继续原工作。");
    }
    static void combat(SkillWork w){interruptOrContinue(w);}
    static void policyChanged(SkillWork w){var state=w.session.state();var reason=w.session.reason();finishDefense(w);if(state==State.PAUSED)w.session.transition(state,reason);w.combat.selected=null;w.combat.clearSearch();w.combat.nextScan=0;w.lastCombatTick=-1;w.positioning.reset();}
    private static void phase(SkillWork w,String name){if(!w.tactic.equals(name)){w.tactic=name;w.tacticAt=w.tick();w.session.add("tactic_"+name,1);}w.session.phase(name);if(w.actor instanceof PlayerSkillActor p&&w.tick()%10==0)p.report(name,false);}
    private static void log(SkillWork w,String state){
        var receipt=new LinkedHashMap<>(w.session.receipt());receipt.put("combatState",state);receipt.put("combatOperation",w.combatOperation==null?"":w.combatOperation.toString());receipt.put("combatActorEntityId",Integer.toString(w.player().getId()));receipt.put("combatIntentRevision",Long.toString(w.session.revision()));receipt.put("combatTarget",w.fighting==null?"":w.fighting.toString());receipt.put("arrowsAfter",Integer.toString(w.count(Items.ARROW)));w.session.receipt(receipt);
        // Persistence is asynchronous; tactics and urgent defense never wait for this journal.
        w.runtime.persist(w);
    }
    private static void observeRelease(SkillWork w){
        if(w.combatStage==2&&(w.ranged.adapter.isEmpty()||w.ranged.adapter.equals("minecraft:bow"))&&!w.shotLogged&&w.count(Items.ARROW)<w.combatAmmo){w.shotLogged=true;w.session.add("arrowsReleased",1);log(w,"RELEASE_OBSERVED");}
    }
    private static boolean move(SkillWork w,Vec3 next,LivingEntity target,boolean escape){
        if(next==null){
            if(!w.positioning.pending()&&target!=null){if(!w.actor.recovering()){w.actor.stop(w.token());w.combatOperation=w.shieldOperation=w.healingOperation=null;w.combatStage=0;}if(w.actor.recover(w.token(),target.position())){phase(w,"TERRAIN_ESCAPE");return true;}}
            w.actor.haltMotion(w.token());phase(w,w.positioning.pending()?"WAITING_FOR_TACTICAL_PATH":"NO_SAFE_EXIT");shield(w,target);return false;
        }
        boolean sprintEscape=escape&&(w.positioning.longRetreat()||w.contactEscape);
        var heading=new Vec3(next.x-w.player().getX(),0,next.z-w.player().getZ());var look=w.player().getLookAngle();boolean sprintClosing=w.sprintApproach&&!escape&&w.tick()-w.lastAttackAt>=2&&heading.lengthSqr()>.001&&new Vec3(look.x,0,look.z).normalize().dot(heading.normalize())>.75;
        if((sprintEscape||sprintClosing)&&w.player().isUsingItem()&&!w.player().getUseItem().getOrDefault(DataComponents.USE_EFFECTS,net.minecraft.world.item.component.UseEffects.DEFAULT).canSprint()){w.actor.stop(w.token());w.combatStage=0;w.combatOperation=null;w.shieldOperation=null;w.healingOperation=null;}
        w.actor.sprint(w.token(),sprintEscape||sprintClosing);
        if(target!=null&&!sprintEscape)w.actor.aim(w.token(),target.getEyePosition());
        else {var direction=new Vec3(next.x-w.player().getX(),0,next.z-w.player().getZ());if(direction.lengthSqr()>.001)w.actor.aim(w.token(),w.player().getEyePosition().add(direction.normalize().scale(4)));}
        if(w.actor.moveTactically(w.token(),w.positioning.route()).equals("ROUTE_CHANGED")){w.actor.haltMotion(w.token());w.positioning.reset();return false;}return true;
    }
    private static void baitRanged(SkillWork w,LivingEntity target,NativeCombatStates.Snapshot actual){
        double range=actual.attacks().stream().filter(a->a.kind().equals("RANGED")).mapToDouble(NativeCombatStates.Attack::maxRange).max().orElse(12);
        w.sprintApproach=false;phase(w,"RANGED_BAIT");w.session.add("rangedRepositions",1);
        move(w,w.positioning.choose(w,"RETREAT",range+3),target,true);
    }
    private static void fight(SkillWork w,LivingEntity target){
        var p=w.player();var rule=w.session.spec().combat();w.sprintApproach=false;
        if(target!=null&&(!SkillRuntime.attackAllowed(w,target))){w.combat.selected=null;return;}
        if(w.actor.recovering()&&target!=null){w.actor.recover(w.token(),target.position());phase(w,"TERRAIN_ESCAPE");return;}
        int contacts=w.combat.contacts(w);boolean flanked=w.combat.flanked(w);
        if(target!=null&&!target.onGround()&&target.distanceTo(p)<8&&w.prediction.risk(w,p.position(),6,null)>=18
                &&rule.strategy()!=CombatPolicy.Strategy.HOLD_POSITION){
            if(sideStep(w,target,p.getAttackRangeWith(p.getMainHandItem()).effectiveMaxRange(p)+1)){w.session.add("predictedAirborneEvasions",1);return;}
        }
        boolean openingCounter=counterBeforeEscape(w,target,contacts);
        if(openingCounter&&w.contactEscape){w.contactEscape=false;w.contactClearSince=-1;w.positioning.reset();w.session.add("contactCounterOpenings",1);}
        if(!openingCounter&&escapeContact(w,target,contacts))return;
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
        if(NativeRangedCombat.tick(w,target))return;
        double distance=p.distanceTo(target);boolean visible=p.hasLineOfSight(target);
        var actual=NativeCombatStates.read(target,p);
        double enemyReach=actual.attacks().stream().filter(a->a.kind().equals("MELEE")).mapToDouble(NativeCombatStates.Attack::maxRange).max().orElse(0);
        withdrawal=Math.max(withdrawal,enemyReach+p.getBbWidth()/2+target.getBbWidth()/2+1);
        if(w.combatStage!=0){w.actor.stop(w.token());w.combatStage=0;w.combatOperation=null;}
        if(!CombatEquipmentAdapter.melee(w,target)){w.actor.haltMotion(w.token());return;}
        double reach=p.getAttackRangeWith(p.getMainHandItem()).effectiveMaxRange(p);
        boolean ready=p.getAttackStrengthScale(.5f)>=.95f;
        boolean inReach=p.isWithinAttackRange(p.getMainHandItem(),target.getHitbox(),0)&&visible;
        int opening=actual.openingTicks(p.level().getGameTime());
        double speed=p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)*(p.getFoodData().getFoodLevel()>6?2.8:2.1);
        int closing=CombatOpening.closingTicks(distance,reach,speed);
        int ownCooldown=(int)Math.ceil((1-p.getAttackStrengthScale(.5f))*20/Math.max(.1,p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED)));
        boolean counter=CombatOpening.canCounter(opening,closing,ownCooldown,w.combat.incoming(w));
        // A distant archer's reload can also buy a short burst of progress between volleys.
        boolean advanceBetweenShots=actual.ranged()&&opening>=10&&ownCooldown<opening-4&&health>.45&&!w.combat.incoming(w);
        if(!inReach&&visible&&(counter||advanceBetweenShots)&&rule.strategy()!=CombatPolicy.Strategy.HOLD_POSITION){
            if(!w.tactic.equals("COUNTER_APPROACH")){w.session.add("counterOpenings",1);if(actual.meleeRestricted())w.session.add("counterStunOpenings",1);else w.session.add("counterCooldownOpenings",1);}
            phase(w,"COUNTER_APPROACH");w.sprintApproach=true;
            if(!move(w,w.positioning.choose(w,"COUNTER",Math.max(1,reach-.2)),target,false)){if(!w.positioning.pending()&&actual.ranged())baitRanged(w,target,actual);return;}
            if(counter&&distance>reach+1&&opening>closing+8&&w.tick()-w.lastTacticalJump>=20&&w.positioning.jumpSafe(w)&&p.isSprinting()){
                w.jumpKind="COUNTER";w.tacticalJumpY=p.getY();w.actor.jump(w.token());w.lastTacticalJump=w.tick();w.session.add("counterJumpAttempts",1);
            }
            return;
        }
        if(rule.strategy()!=CombatPolicy.Strategy.HOLD_POSITION&&rule.strategy()!=CombatPolicy.Strategy.DISENGAGE){
            if(jumpTap(w,target,withdrawal))return;
            if(w.tick()-w.lastAttackAt>=3&&(!ready||w.combat.incoming(w))&&sideStep(w,target,reach+.4))return;
        }
        boolean combo=rule.strategy()==CombatPolicy.Strategy.MELEE_COMBO||rule.strategy()==CombatPolicy.Strategy.AUTO&&w.combat.threats.size()==1&&health>.5;
        boolean knockedAway=w.tick()-w.lastMeleeHitTick<24&&target.getUUID().equals(w.lastMeleeHitTarget)&&actual.velocity().dot(target.position().subtract(p.position()))>.01;
        if(combo&&!ready){
            double spacing=reach+.2;
            if(knockedAway&&distance>reach-.3&&!actual.inNativeMeleeRange()){phase(w,"COMBO_PRESSURE");w.sprintApproach=true;move(w,w.positioning.choose(w,"APPROACH",Math.max(1.5,reach-.4)),target,false);}
            else if(w.tick()-w.lastAttackAt<3||distance<spacing){phase(w,"STAP_SPACE");move(w,w.positioning.choose(w,"SPACE",spacing+.2),target,false);}
            else {phase(w,"COMBO_SPACING");w.actor.aim(w.token(),target.getEyePosition());w.actor.haltMotion(w.token());shield(w,target);}
            return;
        }
        boolean attackedRecently=w.tick()-w.lastAttackAt<(combo?3:8)||!ready;
        boolean contactDanger=actual.inNativeMeleeRange()&&!actual.meleeRestricted()&&actual.attacks().stream().anyMatch(a->a.running()&&a.kind().equals("MELEE")&&(a.cooldownTicks()<0||a.cooldownTicks()<5));
        if(rule.strategy()!=CombatPolicy.Strategy.HOLD_POSITION&&(attackedRecently||contactDanger&&!(inReach&&ready)||actual.areaAttack())){
            phase(w,"MELEE_EXIT");
            boolean longReach=enemyReach>reach;
            move(w,w.positioning.choose(w,"RETREAT",withdrawal),target,longReach);
            if(!longReach||!w.positioning.longRetreat())shield(w,target);return;
        }
        if(!inReach){
            phase(w,"MELEE_APPROACH");
            if(rule.strategy()==CombatPolicy.Strategy.HOLD_POSITION){w.actor.aim(w.token(),target.getEyePosition());w.actor.haltMotion(w.token());shield(w,target);return;}
            w.sprintApproach=true;if(!move(w,w.positioning.choose(w,"APPROACH",Math.max(1,reach-.2)),target,false)&&!w.positioning.pending()&&actual.ranged())baitRanged(w,target,actual);return;
        }
        if(!ready){phase(w,"COOLDOWN_GUARD");shield(w,target);return;}
        Vec3 exit=w.positioning.choose(w,"RETREAT",withdrawal);
        if(exit==null&&rule.strategy()!=CombatPolicy.Strategy.HOLD_POSITION){phase(w,"NO_SAFE_EXIT");shield(w,target);return;}
        if(!CombatEquipmentAdapter.melee(w,target))return;
        if(p.isUsingItem())p.stopUsingItem();
        w.actor.aim(w.token(),target.getEyePosition());
        if(!ActorEnhancements.boost(p)&&CombatCriticalTiming.waitOrJump(w,target)){phase(w,"NORMAL_CRITICAL_WINDOW");return;}
        if(w.combatOperation==null||w.tick()-w.combatAt>5){w.combatOperation=UUID.randomUUID();w.combatAt=w.tick();w.session.add("meleeAttempts",1);if(counter){w.session.add("counterOpenings",1);w.session.add("counterStrikeAttempts",1);}}
        if(target.isBlocking()&&p.getMainHandItem().is(ItemTags.AXES)){w.shieldCounterTarget=target;w.shieldCounterAt=w.tick();w.shieldCounterItem=target.getUseItem().copy();w.session.add("shieldCounterAttempts",1);}
        w.actor.attack(w.token(),w.combatOperation,target);
        // Client attacks are acknowledged by native cooldown/damage; never assume a hit or knockback.
        if(p.getAttackStrengthScale(.5f)<.8f||w.lastHitAt>=w.combatAt){
            w.lastAttackAt=w.tick();log(w,"NATIVE_ATTACK_OBSERVED");w.combatOperation=null;
            boolean pushing=combo&&target.getDeltaMovement().dot(target.position().subtract(p.position()))>.01&&!NativeCombatStates.meleeAt(target,p,p.position());
            phase(w,pushing?"COMBO_PRESSURE":combo?"STAP_SPACE":"MELEE_EXIT");w.sprintApproach=pushing;
            if(rule.strategy()!=CombatPolicy.Strategy.HOLD_POSITION)move(w,pushing?w.positioning.choose(w,"APPROACH",Math.max(1.5,reach-.4)):exit,target,!pushing&&enemyReach>reach);
        }
    }
    private static void observeFootwork(SkillWork w){
        var position=w.player().position();var target=w.combat.selected;
        if(w.footworkPosition!=null&&target!=null&&w.sideStepUntil>=w.tick()){
            var toward=target.position().subtract(position).multiply(1,0,1).normalize();double moved=position.subtract(w.footworkPosition).dot(new Vec3(toward.z,0,-toward.x));
            if(Math.abs(moved)>.006){w.session.add(moved>0?"nativeStrafeLeftTicks":"nativeStrafeRightTicks",1);w.session.add("nativeStrafeDistanceMilli",(long)(Math.abs(moved)*1000));}
            if(!NativeCombatStates.meleeAt(target,w.player(),position))w.session.add("strafeOutsideNativeAttackBoxTicks",1);
        }w.footworkPosition=position;
    }
    private static boolean sideStep(SkillWork w,LivingEntity target,double spacing){
        if(target==null||w.player().isInWater()||w.player().isCrouching()||w.combat.contacts(w)>1||w.contactEscape)return false;
        double left=w.positioning.sideRisk(w,1),right=w.positioning.sideRisk(w,-1);int before=w.footwork.side(),side=w.footwork.choose(w.tick(),left,right);
        if(side==0)return false;var point=w.positioning.choose(w,side>0?"SIDE_LEFT":"SIDE_RIGHT",spacing);if(point==null)return false;
        if(w.player().isUsingItem()){w.actor.stop(w.token());w.shieldOperation=null;w.healingOperation=null;}
        w.sprintApproach=false;phase(w,side>0?"AD_LEFT":"AD_RIGHT");if(side!=before)w.session.add("adDirectionChanges",1);w.sideStepUntil=w.tick()+3;
        return move(w,point,target,false);
    }
    private static boolean jumpTap(SkillWork w,LivingEntity target,double spacing){
        var p=w.player();
        if(w.jumpTapUntil>=w.tick()&&w.tick()-w.lastTacticalJump<14){
            phase(w,"JUMP_TAP");w.sprintApproach=false;return move(w,w.positioning.choose(w,"JUMP_TAP",spacing),target,false);
        }
        if(target==null||w.tick()-w.lastMeleeHitTick>4||!target.getUUID().equals(w.lastMeleeHitTarget)||w.tick()-w.lastTacticalJump<28||w.combat.contacts(w)>1||p.getHealth()<p.getMaxHealth()*.4)return false;
        var point=w.positioning.choose(w,"JUMP_TAP",spacing);if(point==null)return false;
        boolean grounded=p.onGround()||p.getDeltaMovement().y<=.01&&!p.level().noCollision(p,p.getBoundingBox().move(0,-.04,0));
        if(!w.footwork.jumpReady(w.tick(),grounded,true,w.positioning.jumpTapSafe(w)))return false;
        w.sprintApproach=false;phase(w,"JUMP_TAP");if(!move(w,point,target,false))return false;
        w.footwork.jumped(w.tick());w.jumpKind="JUMP_TAP";w.tacticalJumpY=p.getY();w.lastTacticalJump=w.tick();w.jumpTapUntil=w.tick()+12;
        w.actor.jump(w.token());w.session.add("jumpTapAttempts",1);return true;
    }
    /** Actual health damage plus continuing native melee contact outranks ordinary pursuit and equipment choice. */
    private static boolean counterBeforeEscape(SkillWork w,LivingEntity target,int contacts){
        var p=w.player();if(target==null||contacts>1||p.getHealth()<p.getMaxHealth()*.5||p.getAttackStrengthScale(.5f)<.95f||w.session.spec().combat().strategy()==CombatPolicy.Strategy.DISENGAGE)return false;
        var actual=NativeCombatStates.read(target,p);double reach=p.getAttackRangeWith(p.getMainHandItem()).effectiveMaxRange(p);
        double speed=p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)/(p.isSprinting()?1.3:1)*(p.getFoodData().getFoodLevel()>6?2.8:2.1);
        return CombatOpening.canCounter(actual.openingTicks(p.level().getGameTime()),CombatOpening.closingTicks(p.distanceTo(target),reach,speed),0,w.combat.incoming(w));
    }
    /** Actual damage still forces withdrawal whenever there is no verified counterattack window. */
    private static boolean escapeContact(SkillWork w,LivingEntity target,int contacts){
        if(contacts>0){if(w.contactSince<0)w.contactSince=w.tick();}else w.contactSince=-1;
        if(!w.contactEscape&&contacts>0&&w.tick()-w.contactSince>=1&&w.tick()-w.lastContactDamage<=20){
            observeRelease(w);w.actor.stop(w.token());w.combatStage=0;w.combatOperation=null;w.shieldOperation=null;w.healingOperation=null;
            w.contactEscape=true;w.contactRunAndHit=true;w.contactClearSince=-1;w.contactEscapeOrigin=w.player().position();w.contactEscapeLastPosition=w.player().position();w.positioning.reset();w.session.add("contactEscapes",1);w.notice("contact_escape","先拉开距离，再继续跑打。");
        }
        if(!w.contactEscape)return false;
        boolean clear=contacts==0&&!w.combat.flanked(w)&&w.combat.risk(w,w.player().position())<4;
        if(clear){if(w.contactClearSince<0)w.contactClearSince=w.tick();}else w.contactClearSince=-1;
        if(w.contactClearSince>=0&&w.tick()-w.contactClearSince>=6&&w.tick()-w.lastContactDamage>=10){
            w.contactEscape=false;w.contactSince=w.contactClearSince=-1;w.actor.haltMotion(w.token());w.positioning.reset();w.lastAttackAt=w.tick();w.session.add("contactEscapeResumptions",1);w.session.add("nativeMeleeClearanceVerified",1);return false;
        }
        phase(w,"CONTACT_ESCAPE");
        if(w.player().isSprinting()&&w.contactEscapeLastPosition!=null){double travel=w.player().position().distanceTo(w.contactEscapeLastPosition);if(travel>.001){w.session.add("nativeContactSprintTicks",1);w.session.add("nativeContactSprintDistanceMilli",(long)(travel*1000));}}w.contactEscapeLastPosition=w.player().position();
        if(w.contactEscapeOrigin!=null)w.session.add("contactEscapeDistanceMilli",Math.max(0,(long)(w.player().position().distanceTo(w.contactEscapeOrigin)*1000)-w.session.count("contactEscapeDistanceMilli")));
        Vec3 exit=w.positioning.choose(w,"RETREAT",Math.max(7,w.player().getAttackRangeWith(w.player().getMainHandItem()).effectiveMaxRange(w.player())+4));
        move(w,exit,target,true);breakthrough(w,exit);
        return true;
    }
    private static void breakthrough(SkillWork w,Vec3 exit){
        if(w.session.spec().combat().engagement()==CombatPolicy.Engagement.NONE)return;
        var p=w.player();var corridor=exit==null?p.getBoundingBox().inflate(3):p.getBoundingBox().expandTowards(exit.subtract(p.position())).inflate(.35);
        var blocker=w.combat.threats.stream().filter(t->t.eligible()&&t.entity().isAlive()&&corridor.intersects(t.entity().getBoundingBox())&&p.hasLineOfSight(t.entity())&&p.isWithinAttackRange(p.getMainHandItem(),t.entity().getHitbox(),0)).min(Comparator.comparingDouble(t->p.distanceToSqr(t.entity()))).orElse(null);
        if(blocker==null||p.getAttackStrengthScale(.5f)<.95f||!weapon(w))return;
        phase(w,"BREAKTHROUGH");w.fighting=blocker.entity().getUUID();w.actor.aim(w.token(),blocker.entity().getEyePosition());
        if(w.combatOperation==null||w.tick()-w.combatAt>5){w.combatOperation=UUID.randomUUID();w.combatAt=w.tick();w.session.add("breakthroughAttempts",1);}
        w.actor.attack(w.token(),w.combatOperation,blocker.entity());
        if(p.getAttackStrengthScale(.5f)<.8f||w.lastHitAt>=w.combatAt){w.lastAttackAt=w.tick();log(w,"NATIVE_BREAKTHROUGH_ATTACK_OBSERVED");w.combatOperation=null;}
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
        var p=w.player();if(target==null||w.actor.recovering())return false;
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
