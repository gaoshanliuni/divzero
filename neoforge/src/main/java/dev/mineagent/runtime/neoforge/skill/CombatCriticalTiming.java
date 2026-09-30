package dev.mineagent.runtime.neoforge.skill;

import net.minecraft.world.entity.LivingEntity;
import dev.mineagent.runtime.neoforge.body.NativeTraversalEvaluator;
import dev.mineagent.runtime.neoforge.mixin.CombatCriticalAccess;

/** Normal jump crits use real jump/fall state; post-hit Jump-tap remains a different action. */
final class CombatCriticalTiming {
    static boolean waitOrJump(SkillWork work,LivingEntity target){
        var body=work.player();
        if(((CombatCriticalAccess)body).divzero$canCriticalAttack(target)){work.criticalStarted=-10000;return false;}
        if(body.isInWater()||body.isPassenger()||body.onClimbable()||body.getGravity()<=0)return false;
        int age=work.tick()-work.criticalStarted;
        if(age<=14&&target.getUUID().equals(work.criticalTarget)){
            if(body.distanceTo(target)>body.getAttackRangeWith(body.getMainHandItem()).effectiveMaxRange(body)+1.5||work.combat.contacts(work)>1){work.criticalStarted=-10000;return false;}
            work.actor.sprint(work.token(),false);work.actor.aim(work.token(),target.getEyePosition());
            if(body.onGround()&&age<4)work.actor.jump(work.token());else work.actor.haltMotion(work.token());
            return !body.onGround()||age<4;
        }
        // Take an existing ascending opportunity only for a short, checked wait.
        double vertical=body.getDeltaMovement().y;
        if(!body.onGround()&&vertical>0&&vertical/body.getGravity()<=4&&work.combat.contacts(work)<=1){
            work.actor.sprint(work.token(),false);work.actor.aim(work.token(),target.getEyePosition());return true;
        }
        if(!body.onGround()||work.tick()-work.lastCriticalJump<36||body.getHealth()<body.getMaxHealth()*.6||work.combat.contacts(work)>1||work.combat.incoming(work))return false;
        if(body.distanceTo(target)>body.getAttackRangeWith(body.getMainHandItem()).effectiveMaxRange(body)-.35)return false;
        if(NativeCombatStates.read(target,body).openingTicks(body.level().getGameTime())<5)return false;
        var evaluator=new NativeTraversalEvaluator(body);var feet=evaluator.closest(body.position());
        if(feet==null||evaluator.neighbors(feet).size()<2||!body.level().noCollision(body,body.getBoundingBox().expandTowards(0,1.3,0)))return false;
        for(var edge:evaluator.neighbors(feet))if(edge.to().y()<feet.y()-1.25)return false;
        work.criticalStarted=work.lastCriticalJump=work.tick();work.criticalTarget=target.getUUID();
        work.actor.haltMotion(work.token());work.actor.sprint(work.token(),false);work.actor.aim(work.token(),target.getEyePosition());work.actor.jump(work.token());
        work.session.add("normalCriticalJumpAttempts",1);return true;
    }
    private CombatCriticalTiming(){}
}
