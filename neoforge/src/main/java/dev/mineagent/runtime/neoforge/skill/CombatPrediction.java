package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.core.task.MotionForecast;
import dev.mineagent.runtime.core.task.SurfacePathfinder.PathStep;
import dev.mineagent.runtime.neoforge.body.NativeTraversalEvaluator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Cached observations and collision-constrained branches, reused by all candidates in one decision. */
final class CombatPrediction {
    private record Observation(Vec3 position,Vec3 velocity,int tick){}
    private final Map<UUID,Observation> history=new HashMap<>();
    private final Map<UUID,List<List<MotionForecast.Sample>>> predictions=new HashMap<>();
    private int tick=-1,decisionTick=-1;
    void observe(SkillWork work,List<LivingEntity> entities){
        int now=work.tick();if(now==tick)return;tick=now;predictions.clear();
        var live=new HashSet<UUID>();
        for(var entity:entities){live.add(entity.getUUID());var old=history.get(entity.getUUID());
            var velocity=old!=null&&now>old.tick?entity.position().subtract(old.position).scale(1d/(now-old.tick)):entity.getDeltaMovement();
            if(velocity.lengthSqr()>4)velocity=entity.getDeltaMovement();
            history.put(entity.getUUID(),new Observation(entity.position(),velocity,now));
        }history.keySet().retainAll(live);
    }
    private List<List<MotionForecast.Sample>> paths(SkillWork work,LivingEntity entity){
        if(decisionTick!=work.tick()){decisionTick=work.tick();predictions.clear();}
        return predictions.computeIfAbsent(entity.getUUID(),id->{
            var observed=history.get(id);var velocity=observed==null?entity.getDeltaMovement():observed.velocity;
            var origin=entity.position();var bounds=entity.getBoundingBox();var level=entity.level();
            double speed=entity.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)==null?.1:entity.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
            return MotionForecast.predict(new MotionForecast.Input(point(origin),point(velocity),entity.onGround(),entity.isNoGravity()?0:entity.getGravity(),Math.max(.02,speed*.3),observed==null?10:work.tick()-observed.tick,entity instanceof net.minecraft.world.entity.player.Player?point(entity.getLookAngle()):null),new MotionForecast.Collision(){
                public MotionForecast.Point move(MotionForecast.Point from,MotionForecast.Point requested){
                    var at=vec(from);var displacement=vec(requested);var next=at.add(displacement);
                    if(!level.hasChunkAt(BlockPos.containing(next)))return from;
                    if(entity instanceof net.minecraft.world.entity.monster.Vex)return point(next);
                    if(level.noCollision(entity,bounds.move(next.subtract(origin))))return point(next);
                    var vertical=at.add(0,displacement.y,0);if(level.noCollision(entity,bounds.move(vertical.subtract(origin))))at=vertical;
                    var horizontal=at.add(displacement.x,0,displacement.z);if(level.noCollision(entity,bounds.move(horizontal.subtract(origin))))at=horizontal;
                    return point(at);
                }
                public boolean supported(MotionForecast.Point at){return !(entity instanceof net.minecraft.world.entity.monster.Vex)&&!level.noCollision(entity,bounds.move(vec(at).subtract(origin)).move(0,-.06,0));}
            },24);
        });
    }
    Vec3 intercept(SkillWork work,LivingEntity target,double ticks){
        var values=paths(work,target).getFirst();return vec(values.get(Math.clamp((int)Math.round(ticks)-1,0,values.size()-1)).position());
    }
    double risk(SkillWork work,Vec3 self,int atTick,LivingEntity opportunity){
        double risk=0;for(var threat:work.combat.threats){var entity=threat.entity();if(!entity.isAlive())continue;
            double worst=0;for(var branch:paths(work,entity)){
                var sample=branch.get(Math.clamp(atTick-1,0,branch.size()-1));var drift=vec(sample.position()).subtract(entity.position());
                // Translate the tested observer position into the enemy's observed frame; preserve native reach shapes.
                var relative=self.subtract(drift);double uncertainty=sample.uncertainty();boolean exposed=NativeCombatStates.meleeAt(entity,work.player(),relative);
                if(!exposed){var toward=entity.position().subtract(relative).normalize();exposed=NativeCombatStates.meleeAt(entity,work.player(),relative.add(toward.scale(uncertainty)));}
                if(exposed)worst=Math.max(worst,threat.state().openingTicks(work.player().level().getGameTime())>atTick+2?2:entity==opportunity?4:18);
                if(threat.state().areaAttack()&&vec(sample.position()).distanceTo(self)<8)worst=Math.max(worst,30);
            }risk+=worst;
        }return risk;
    }
    double routeRisk(SkillWork work,List<PathStep> route,LivingEntity opportunity){
        Vec3 at=work.player().position();double speed=Math.max(.08,work.player().getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)*2.2),worst=0;int elapsed=0;
        for(var edge:route){var end=NativeTraversalEvaluator.point(edge.to());int duration=Math.max(1,(int)Math.ceil(at.distanceTo(end)/speed));
            for(int t=1;t<=duration&&elapsed+t<=18;t++){var sample=at.lerp(end,t/(double)duration);worst=Math.max(worst,risk(work,sample,elapsed+t,opportunity)+work.combat.spells.risk(work,sample,elapsed+t));}
            elapsed+=duration;if(elapsed>=18)break;at=end;
        }return worst;
    }
    private static MotionForecast.Point point(Vec3 value){return new MotionForecast.Point(value.x,value.y,value.z);}
    private static Vec3 vec(MotionForecast.Point value){return new Vec3(value.x(),value.y(),value.z());}
}
