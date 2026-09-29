package dev.mineagent.runtime.neoforge.skill;
import dev.mineagent.runtime.core.task.SkillSpec;import dev.mineagent.runtime.core.task.SkillSession.State;
import net.minecraft.world.entity.*;import net.minecraft.world.entity.monster.Enemy;import net.minecraft.world.item.*;import net.minecraft.tags.ItemTags;import net.minecraft.world.phys.Vec3;import java.util.*;

/** Native cooldowns, item charging and gravity; observations distinguish shots from damage. */
final class CombatSkill {
    static boolean interruptOrContinue(SkillWork w){
        observeRelease(w);
        if(w.session.spec().kind()==SkillSpec.Kind.COMBAT)return false;
        if(w.fighting==null){if(!w.session.spec().defend()&&w.session.spec().kind()!=SkillSpec.Kind.GUARD||w.tick()%10!=0)return false;
            var threats=w.player().level().getEntitiesOfClass(Mob.class,w.player().getBoundingBox().inflate(8),e->e.isAlive()&&(e instanceof Enemy||e.getTarget()==w.player())&&e.hasLineOfSight(w.player())&&(w.session.spec().kind()!=SkillSpec.Kind.GUARD||w.session.spec().area().contains(new SkillSpec.Point(e.getX(),e.getY(),e.getZ()))));
            var target=threats.stream().min(Comparator.comparingDouble(w.player()::distanceToSqr)).orElse(null);if(target==null)return false;
            w.fighting=target.getUUID();w.suspendedPhase=w.session.phase();w.interruptedOperation=w.operation!=null;w.actor.stop(w.token());w.actor.controls().release(w.token());w.runtime.reservations.release(w.token());w.combatStage=0;w.combatOperation=null;w.acquire();w.runtime.persist(w);
        }
        var target=w.player().level().getEntity(w.fighting);if(!(target instanceof LivingEntity living)||!living.isAlive()||w.player().distanceToSqr(living)>24*24){w.actor.stop(w.token());w.fighting=null;w.combatOperation=null;w.session.phase(w.suspendedPhase==null?"SCAN":w.suspendedPhase);w.stand=null;w.search=null;w.session.transition(State.RUNNING,"DEFENSE_FINISHED_RECHECK_WORK");w.runtime.persist(w);return false;}
        w.session.transition(State.SUSPENDED,"DEFENDING_THEN_RESUME");fight(w,living);return true;
    }
    static void combat(SkillWork w){observeRelease(w);var e=w.player().level().getEntity(UUID.fromString(w.session.spec().target()));if(!(e instanceof LivingEntity target)||!target.isAlive()){w.completed("COMBAT_TARGET_GONE");return;}w.fighting=target.getUUID();fight(w,target);}
    private static void log(SkillWork w,String state){var receipt=new LinkedHashMap<>(w.session.receipt());receipt.put("combatState",state);receipt.put("combatOperation",w.combatOperation==null?"":w.combatOperation.toString());receipt.put("combatActorEntityId",Integer.toString(w.player().getId()));receipt.put("combatIntentRevision",Long.toString(w.session.revision()));receipt.put("combatTarget",w.fighting==null?"":w.fighting.toString());receipt.put("arrowsAfter",Integer.toString(w.count(Items.ARROW)));w.session.receipt(receipt);w.runtime.persist(w);}
    private static void observeRelease(SkillWork w){if(w.combatStage==2&&!w.shotLogged&&w.count(Items.ARROW)<w.combatAmmo){w.shotLogged=true;w.session.add("arrowsReleased",1);log(w,"RELEASE_OBSERVED");}}
    private static void fight(SkillWork w,LivingEntity target){
        if(target instanceof net.minecraft.world.entity.player.Player){w.pause("COMBAT_PLAYER_TARGET_NOT_SUPPORTED");return;}
        var p=w.player();double distance=p.distanceTo(target);boolean visible=p.hasLineOfSight(target);var targetPoint=target.getEyePosition();
        if(p.getHealth()<p.getMaxHealth()*.25){var away=p.position().subtract(target.position());if(away.horizontalDistanceSqr()>.01)w.actor.move(w.token(),p.position().add(away.normalize().scale(4)));w.actor.aim(w.token(),targetPoint);if(p.getOffhandItem().is(Items.SHIELD)){if(w.combatOperation==null)w.combatOperation=UUID.randomUUID();w.actor.useItem(w.token(),w.combatOperation,true);}w.session.phase("COMBAT_RETREAT");return;}
        boolean ranged=distance>5&&w.count(Items.BOW)>0&&(w.count(Items.ARROW)>0||p.hasInfiniteMaterials());
        if(ranged&&visible&&distance<24){
            if(!w.equip(Items.BOW)){w.nextTick=w.tick()+2;return;}
            // Low ballistic arc for vanilla full-draw arrow speed 3, gravity .05. No no-gravity arrows.
            double dx=targetPoint.x-p.getX(),dz=targetPoint.z-p.getZ(),r=Math.hypot(dx,dz),dy=targetPoint.y-p.getEyeY(),v2=9,g=.05,discriminant=v2*v2-g*(g*r*r+2*dy*v2);
            if(discriminant<0){w.actor.move(w.token(),target.position());return;}double slope=(v2-Math.sqrt(discriminant))/(g*Math.max(.01,r));var aim=new Vec3(targetPoint.x,p.getEyeY()+slope*r,targetPoint.z);w.actor.aim(w.token(),aim);
            if(w.combatStage==0){w.actor.stop(w.token());w.actor.aim(w.token(),aim);w.combatOperation=UUID.randomUUID();w.combatAt=w.tick();w.combatStage=1;w.shotLogged=false;w.combatAmmo=w.count(Items.ARROW);w.session.phase("BOW_DRAW");w.session.add("bowAttempts",1);log(w,"PREPARED");return;}
            if(!w.saved.isDone())return;
            if(w.combatStage==1){w.actor.useItem(w.token(),w.combatOperation,true);if(p.isUsingItem()&&p.getTicksUsingItem()>=20){w.combatStage=2;w.combatAt=w.tick();}else if(w.tick()-w.combatAt>100){w.actor.stop(w.token());w.combatStage=0;w.nextTick=w.tick()+20;}return;}
            if(w.combatStage==2){w.actor.releaseItem(w.token(),w.combatOperation);observeRelease(w);if(!p.isUsingItem()&&w.tick()-w.combatAt>5){log(w,w.shotLogged?"RELEASE_OBSERVED":"USE_ENDED_NO_CONSUMPTION_PROOF");w.combatStage=0;w.combatOperation=null;w.nextTick=w.tick()+15;}return;}
        }
        if(w.combatStage!=0){w.actor.stop(w.token());w.combatStage=0;w.combatOperation=null;}
        if(!visible||distance>2.7){w.actor.move(w.token(),target.position());w.actor.aim(w.token(),targetPoint);w.session.phase("COMBAT_CHASE");return;}
        w.actor.aim(w.token(),targetPoint);w.session.phase("COMBAT_MELEE");
        if(w.combatOperation!=null){if(!w.saved.isDone())return;if(w.tick()-w.combatAt<12){w.actor.attack(w.token(),w.combatOperation,target);return;}log(w,"ATTACK_WINDOW_ENDED");w.combatOperation=null;w.nextTick=w.tick()+3;return;}
        if(p.getAttackStrengthScale(.5f)<.95f)return;
        if(!(p.getMainHandItem().is(ItemTags.SWORDS)||p.getMainHandItem().is(ItemTags.AXES)))for(int i=0;i<36;i++){var stack=p.getInventory().getItem(i);if(stack.is(ItemTags.SWORDS)||stack.is(ItemTags.AXES)){if(!w.actor.select(w.token(),i))return;break;}}
        w.actor.stop(w.token());w.actor.aim(w.token(),targetPoint);w.combatOperation=UUID.randomUUID();w.combatAt=w.tick();w.session.add("meleeAttempts",1);log(w,"PREPARED");
    }
    private CombatSkill(){}
}
