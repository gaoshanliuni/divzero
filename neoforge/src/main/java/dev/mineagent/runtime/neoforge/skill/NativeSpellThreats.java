package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.neoforge.mixin.CombatFangsAccess;
import net.minecraft.world.entity.monster.illager.Evoker;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Real released fangs remain threats after the caster stops casting or dies. Predictions are labelled separately. */
final class NativeSpellThreats {
    record Hazard(UUID source,UUID caster,AABB bounds,int strikeInTicks,float yaw,boolean released,String kind){}
    private int observedAt=-1;
    private List<Hazard> hazards=List.of();
    void observe(SkillWork work){
        if(observedAt==work.tick())return;observedAt=work.tick();var p=work.player();var next=new ArrayList<Hazard>();
        for(var f:p.level().getEntitiesOfClass(EvokerFangs.class,p.getBoundingBox().inflate(24))){
            if(!(f instanceof CombatFangsAccess actual)||actual.divzero$warmupTicks()< -7||!f.isAlive())continue;
            var owner=f.getOwner();if(owner==p||owner!=null&&owner.isAlliedTo(p))continue;
            next.add(new Hazard(f.getUUID(),owner==null?null:owner.getUUID(),f.getBoundingBox().inflate(.2,0,.2),Math.max(1,actual.divzero$warmupTicks()+8),f.getYRot(),true,"RELEASED_FANGS"));
        }
        for(var caster:p.level().getEntitiesOfClass(Evoker.class,p.getBoundingBox().inflate(24),e->e.isAlive()&&!e.isAlliedTo(p)&&p.hasLineOfSight(e))){
            var state=NativeCombatStates.read(caster,p);
            for(var spell:state.spells())if(spell.kind().equals("FANGS")&&spell.preparing()){
                var aim=spell.aimedAt();double angle=Math.atan2(aim.z-caster.getZ(),aim.x-caster.getX());
                double floor=Math.min(aim.y,caster.getY()),ceiling=Math.max(aim.y,caster.getY())+1;
                if(caster.position().distanceToSqr(aim)<9){
                    for(int i=0;i<5;i++)predicted(next,caster,angle+i*Math.PI*.4,1.5,floor,ceiling,spell.releaseInTicks()+8);
                    for(int i=0;i<8;i++)predicted(next,caster,angle+i*Math.PI/4+Math.PI*.4,2.5,floor,ceiling,spell.releaseInTicks()+11);
                }else for(int i=0;i<16;i++)predicted(next,caster,angle,1.25*(i+1),floor,ceiling,spell.releaseInTicks()+8+i);
                work.session.add("observedNativeSpellWarmups",1);
            }
        }
        hazards=List.copyOf(next);if(!hazards.isEmpty())work.session.add("observedSpellThreatTicks",1);
    }
    private static void predicted(List<Hazard> values,Evoker caster,double angle,double distance,double floor,double ceiling,int ticks){
        double x=caster.getX()+Math.cos(angle)*distance,z=caster.getZ()+Math.sin(angle)*distance;
        // Before release the native goal can retarget. Price a conservative footprint, not a promised future entity.
        values.add(new Hazard(caster.getUUID(),caster.getUUID(),new AABB(x-.5,floor,z-.5,x+.5,ceiling+1,z+.5),ticks,(float)Math.toDegrees(angle),false,"TRACKING_FANG_PATTERN"));
    }
    double risk(SkillWork work,Vec3 at,int horizon){
        observe(work);var box=work.player().getBoundingBox().move(at.subtract(work.player().position()));double risk=0;
        for(var hazard:hazards)if(hazard.strikeInTicks<=Math.max(8,horizon)+3&&box.intersects(hazard.bounds))risk+=hazard.released?48:26;
        return risk;
    }
    List<Hazard> view(){return hazards;}
}
