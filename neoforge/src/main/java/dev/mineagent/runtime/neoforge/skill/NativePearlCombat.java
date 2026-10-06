package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.neoforge.body.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.*;
import net.minecraft.world.level.ClipContext;
import java.util.*;

/** Conservative 26.x pearl planning. Only useOnce invokes the native item/cooldown/teleport code. */
final class NativePearlCombat {
    static final class State {int probe=-10000,issued=-10000,before;Vec3 origin,landing;UUID operation;boolean consumed,arrived;}
    record Plan(Vec3 aim,Vec3 landing){}
    static boolean tick(SkillWork w,LivingEntity target){
        var p=w.player();var state=w.pearl;
        if(state.operation!=null){
            if(!state.consumed&&w.count(Items.ENDER_PEARL)<state.before){state.consumed=true;w.session.add("nativePearlsConsumed",state.before-w.count(Items.ENDER_PEARL));}
            if(state.consumed&&!state.arrived&&p.position().distanceTo(state.origin)>4&&p.position().distanceTo(state.landing)<2){state.arrived=true;w.session.add("nativePearlTeleports",1);w.positioning.reset();}
            if(w.tick()-state.issued<3)return true;
        }
        // A confirmed contact escape is already an urgent retreat. Waiting for two heavy hits can
        // leave too little health for the native five-damage teleport, making the safe option expire.
        boolean heavyContact=target!=null&&p.distanceTo(target)<5&&p.getHealth()<=p.getMaxHealth()*.8f;
        boolean retreat=(w.recentDamageChain>=2||w.contactEscape||heavyContact)&&w.tick()-w.lastContactDamage<24;
        // Native pearls can be used during knockback. Requiring ground prevents the escape precisely when needed.
        // The same trajectory planner includes inherited vertical motion and still validates the whole landing area.
        if(target==null||!NativeHumanDuel.pvpParticipant(p)||w.actor.recovering()||!p.onGround()&&!retreat||p.isPassenger()||p.isUsingItem()||w.tick()-state.issued<60||w.tick()-state.probe<12)return false;
        double distance=p.distanceTo(target);
        // Conservative full native pearl damage plus landing margin, even with absorption/protection.
        if(p.getHealth()+p.getAbsorptionAmount()<10||(!retreat&&distance<10)||distance>28)return false;
        int slot=-1;for(int i=0;i<36;i++)if(p.getInventory().getItem(i).is(Items.ENDER_PEARL)&&!p.getCooldowns().isOnCooldown(p.getInventory().getItem(i))){slot=i;break;}
        if(slot<0)return false;state.probe=w.tick();var plan=plan(w,target,retreat);if(plan==null){w.session.add("pearlPlansUnavailable",1);return false;}
        w.actor.stop(w.token());if(!w.actor.select(w.token(),slot)||!p.getMainHandItem().is(Items.ENDER_PEARL))return true;
        w.actor.haltMotion(w.token());w.actor.aimImmediately(w.token(),plan.aim);
        if(p.getLookAngle().dot(plan.aim.subtract(p.getEyePosition()).normalize())<.999)return true;
        state.origin=p.position();state.landing=plan.landing;state.before=w.count(Items.ENDER_PEARL);state.operation=UUID.randomUUID();state.issued=w.tick();state.consumed=state.arrived=false;
        w.combatStage=0;w.combatOperation=null;w.actor.useOnce(w.token(),state.operation,net.minecraft.world.InteractionHand.MAIN_HAND);w.session.add("nativePearlAttempts",1);w.session.add(retreat?"nativeRetreatPearlAttempts":"nativeApproachPearlAttempts",1);return true;
    }
    private static Plan plan(SkillWork w,LivingEntity target,boolean retreat){
        var p=w.player();var budget=NativeNavigationBudget.get(w.runtime.server);if(budget.claim(w.token(),w.tick())==0)return null;
        var terrain=new NativeTraversalEvaluator(p);var toward=target.position().subtract(p.position());double yaw=Math.atan2(-toward.x,toward.z)+(retreat?Math.PI:0);
        double original=p.distanceTo(target),best=retreat?-original-4:original-4;Plan selected=null;
        for(int turn:new int[]{0,-12,12,-24,24})for(int pitch:new int[]{10,20,0,30,-10,40,-20,50}){
            if(!budget.timeAvailable())return selected;
            double heading=yaw+Math.toRadians(turn),angle=Math.toRadians(pitch);var direction=new Vec3(-Math.sin(heading)*Math.cos(angle),-Math.sin(angle),Math.cos(heading)*Math.cos(angle));
            var at=p.getEyePosition().add(0,-.1,0);var inherited=p.getKnownMovement();var velocity=direction.scale(1.5).add(inherited.x,p.onGround()?0:inherited.y,inherited.z);
            for(int t=1;t<=36;t++){
                if(!budget.timeAvailable())return selected;var next=at.add(velocity);var pos=BlockPos.containing(next);
                if(!terrain.loaded(pos)||!p.level().getFluidState(pos).isEmpty())break;
                var sweep=new AABB(at,next).inflate(.4);boolean entity=false;
                for(var other:p.level().getEntities(p,sweep,e->e.isAlive()&&e.isPickable()))if(other.getBoundingBox().inflate(.4).clip(at,next).isPresent()){entity=true;break;}
                if(entity)break;
                var hit=p.level().clip(new ClipContext(at,next,ClipContext.Block.COLLIDER,ClipContext.Fluid.ANY,p));
                if(hit.getType()!=HitResult.Type.MISS){
                    var floor=terrain.closest(at);double distance=at.distanceTo(target.position());double score=retreat?-distance:distance;
                    // Vanilla 26.1.2 uses oldPosition on impact, with native damage=5. Unknown/portal/fluid impacts are refused.
                    if(floor!=null&&at.y>=floor.y()&&at.y-floor.y()<2&&distance>=3&&score+t*.015<best&&safe(terrain,p,at,floor.y())){
                        best=score+t*.015;selected=new Plan(p.getEyePosition().add(direction.scale(16)),at);
                    }break;
                }
                at=next;velocity=velocity.scale(.99).add(0,-.03,0);
            }
        }return selected;
    }
    private static boolean safe(NativeTraversalEvaluator terrain,net.minecraft.server.level.ServerPlayer p,Vec3 at,double y){
        if(!NativeDropSafety.landing(p,at))return false;
        for(double dx:new double[]{-.6,0,.6})for(double dz:new double[]{-.6,0,.6}){
            var sample=at.add(dx,0,dz);var floor=terrain.closest(sample);
            if(floor==null||Math.abs(floor.y()-y)>.1||!terrain.clear(sample,Pose.STANDING,false)||!terrain.clear(new Vec3(sample.x,y,sample.z),Pose.STANDING,false))return false;
        }return true;
    }
    private NativePearlCombat(){}
}
