package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.core.task.DamageSweep;
import dev.mineagent.runtime.neoforge.mixin.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.entity.projectile.*;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.*;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** One immutable local-tick timeline for independent damage channels. Observation never authorizes retaliation. */
public final class NativeAttackTimeline {
    public static final int HORIZON=18;
    public enum Shape { BOX, RADIAL, CYLINDER, TARGET_LOCK }
    /** Interval (tick-1,tick]. BOX sweeps relative to the body; radial shapes add their native distance test. */
    public record Slice(int tick,AABB from,AABB to,Shape shape,Vec3 center,double radius) {
        public Slice {
            Objects.requireNonNull(from);Objects.requireNonNull(to);Objects.requireNonNull(shape);Objects.requireNonNull(center);
            if(tick<1||tick>HORIZON||!Double.isFinite(radius)||radius<0)throw new IllegalArgumentException("ATTACK_SLICE_TIME_OR_RADIUS");
            NativeAttackTimeline.box(from);NativeAttackTimeline.box(to);
        }
        public static Slice box(int tick,AABB from,AABB to){return new Slice(tick,from,to,Shape.BOX,Vec3.ZERO,0);}
    }
    public record Attack(String id,UUID source,UUID owner,String channel,String knowledge,boolean released,double cost,List<Slice> slices) {
        public Attack {
            Objects.requireNonNull(id);Objects.requireNonNull(source);Objects.requireNonNull(channel);Objects.requireNonNull(knowledge);
            if(id.isBlank()||!Double.isFinite(cost)||cost<=0)throw new IllegalArgumentException("ATTACK_ID_OR_COST");
            slices=List.copyOf(slices);
        }
    }
    /** Register actual damage shapes/timers for special native or mod mechanisms; several channels may coexist. */
    public interface Adapter {
        boolean supports(Entity source);
        List<Attack> observe(Entity source,LivingEntity observer,int horizon);
    }
    private static final List<Adapter> ADAPTERS=new CopyOnWriteArrayList<>();
    public static void register(Adapter adapter){ADAPTERS.add(Objects.requireNonNull(adapter));}
    public static void unregister(Adapter adapter){ADAPTERS.remove(adapter);}
    public record Observation(long tick,List<Attack> attacks,List<String> unknown) {}
    private int observedAt=-1;
    private Observation observation=new Observation(-1,List.of(),List.of());

    void observe(SkillWork work){
        if(observedAt==work.tick())return;observedAt=work.tick();
        var actor=work.player();var values=new ArrayList<Attack>();var unknown=new ArrayList<String>();
        collect(actor,Math.max(24,work.session.spec().combat().awareness()),values,unknown);
        observation=new Observation(actor.level().getGameTime(),List.copyOf(values),List.copyOf(unknown));
        if(values.stream().map(Attack::channel).distinct().count()>1)work.session.add("simultaneousAttackChannelTicks",1);
    }
    /** Read-only public diagnostic for adapters and native acceptance, also works without an active combat target. */
    public static Observation inspect(LivingEntity actor,double radius){
        if(actor.level().getServer()==null||!actor.level().getServer().isSameThread())throw new IllegalStateException("ATTACK_OBSERVATION_CONTEXT");
        var values=new ArrayList<Attack>();var unknown=new ArrayList<String>();collect(actor,Math.clamp(radius,1,64),values,unknown);
        return new Observation(actor.level().getGameTime(),List.copyOf(values),List.copyOf(unknown));
    }
    private static void collect(LivingEntity actor,double radius,List<Attack> values,List<String> unknown){
        for(var fang:NativeSpellThreats.observe(actor,radius)){
            int tick=fang.strikeInTicks();if(tick>HORIZON)continue;
            var slices=new ArrayList<Slice>();
            // Forecasts can retarget before release. Real released fangs have exactly one native strike tick.
            for(int t=Math.max(1,tick-(fang.released()?0:2));t<=Math.min(HORIZON,tick+(fang.released()?0:2));t++)slices.add(Slice.box(t,fang.bounds(),fang.bounds()));
            String id=fang.source()+(fang.released()?"/fang":"/forecast/"+fang.bounds().minX+"/"+fang.bounds().minZ);
            values.add(new Attack(id,fang.source(),fang.caster(),"GROUND_STRIKE",fang.kind(),fang.released(),fang.released()?48:26,slices));
        }
        for(var entity:actor.level().getEntities(actor,actor.getBoundingBox().inflate(radius),Entity::isAlive)){
            if(entity instanceof Projectile shot)projectile(actor,shot,values,unknown);
            if(entity instanceof AreaEffectCloud cloud)cloud(actor,cloud,values);
            if(entity instanceof LivingEntity living&&actor.hasLineOfSight(living)){
                if(living instanceof Creeper creeper&&creeper instanceof CombatCreeperAccess fuse&&creeper.getSwellDir()>0){
                    int due=Math.max(1,fuse.divzero$maxSwell()-fuse.divzero$swell());double range=fuse.divzero$explosionRadius()*2*(creeper.isPowered()?2:1);
                    radial(values,living,"EXPLOSION",due,range,living.position(),"NATIVE_FUSE_MOVING_SOURCE",false);
                }
                if(living instanceof Ravager ravager&&ravager.getRoarTick()>10){var bounds=ravager.getBoundingBox().inflate(4);
                    values.add(new Attack(ravager.getUUID()+"/roar",ravager.getUUID(),ravager.getUUID(),"ROAR","NATIVE_ROAR_BOX_TIMER",false,48,List.of(Slice.box(ravager.getRoarTick()-10,bounds,bounds))));}
                if(living instanceof Guardian guardian&&guardian.getTarget()==actor)for(var goal:guardian.goalSelector.getAvailableGoals())if(goal.isRunning()&&goal.getGoal() instanceof CombatGuardianGoalAccess timer){
                    int due=Math.max(1,guardian.getAttackDuration()-timer.divzero$attackTime());if(due<=HORIZON){var volume=guardian.getBoundingBox().inflate(radius*2);
                        values.add(new Attack(guardian.getUUID()+"/beam",guardian.getUUID(),guardian.getUUID(),"TARGET_LOCK","NATIVE_TIMER_REQUIRES_BREAKING_SIGHT",false,42,List.of(new Slice(due,volume,volume,Shape.TARGET_LOCK,guardian.getEyePosition(),0))));}
                }
            }
            for(var adapter:ADAPTERS)try{if(adapter.supports(entity))values.addAll(adapter.observe(entity,actor,HORIZON));}
                catch(RuntimeException failure){unknown.add(entity.getUUID()+"/"+adapter.getClass().getName()+"/"+failure.getClass().getSimpleName());}
        }
    }
    private static void radial(List<Attack> values,LivingEntity source,String channel,int tick,double radius,Vec3 at,String knowledge,boolean released){
        if(tick>HORIZON)return;
        // Future source movement is uncertain. Price the current radius and short observed displacement separately.
        var to=at.add(source.getDeltaMovement().scale(Math.min(4,tick)));
        var bounds=AABB.ofSize(at,radius*2,radius*2,radius*2).minmax(AABB.ofSize(to,radius*2,radius*2,radius*2));
        values.add(new Attack(source.getUUID()+"/"+channel,source.getUUID(),source.getUUID(),channel,knowledge,released,48,List.of(new Slice(tick,bounds,bounds,Shape.RADIAL,at,radius+at.distanceTo(to)))));
    }
    private static void cloud(LivingEntity actor,AreaEffectCloud cloud,List<Attack> values){
        var contents=cloud.get(DataComponents.POTION_CONTENTS);if(contents==null||!actor.isAffectedByPotions())return;
        boolean harmful=false;for(var effect:contents.getAllEffects())if(!effect.getEffect().value().isBeneficial()&&actor.canBeAffected(effect)){harmful=true;break;}if(!harmful)return;
        int retry=cloud instanceof CombatCloudAccess access?access.divzero$victims().getOrDefault(actor,0):0;
        var slices=new ArrayList<Slice>();
        for(int t=1;t<=HORIZON;t++){
            int age=cloud.tickCount+t;if(age<cloud.getWaitTime()||age<retry||age%5!=0||cloud.getDuration()!=-1&&age-cloud.getWaitTime()>=cloud.getDuration())continue;
            double radius=cloud.getRadius()+cloud.getRadiusPerTick()*Math.max(0,age-Math.max(cloud.tickCount,cloud.getWaitTime()));if(radius<.5)continue;
            var bounds=new AABB(cloud.getX()-radius,cloud.getY(),cloud.getZ()-radius,cloud.getX()+radius,cloud.getY()+.5,cloud.getZ()+radius);
            slices.add(new Slice(t,bounds,bounds,Shape.CYLINDER,cloud.position(),radius));
        }
        values.add(new Attack(cloud.getUUID()+"/cloud",cloud.getUUID(),cloud.getOwner()==null?null:cloud.getOwner().getUUID(),"PERSISTENT_AREA","NATIVE_POTION_RADIUS_REAPPLICATION_TIMER",true,34,slices));
    }
    private static void projectile(LivingEntity actor,Projectile shot,List<Attack> values,List<String> unknown){
        if(shot.getOwner()==actor||shot instanceof CombatArrowStateAccess state&&state.divzero$inGround()||shot instanceof CombatProjectileAccess access&&!access.divzero$canHit(actor))return;
        var velocity=shot.getDeltaMovement();if(velocity.lengthSqr()<.00001)return;
        boolean nativeType=shot.getClass().getName().startsWith("net.minecraft.");
        boolean arrow=shot instanceof AbstractArrow,thrown=shot instanceof ThrowableProjectile,fireball=shot instanceof AbstractHurtingProjectile;
        boolean known=nativeType&&(arrow||thrown||fireball);
        if(!known)unknown.add(shot.getUUID()+"/PROJECTILE_MOTION_ESTIMATE");
        var slices=new ArrayList<Slice>();var impacts=new ArrayList<Slice>();var at=shot.position();
        for(int tick=1;tick<=HORIZON;tick++){
            // These are current native physical values, not a promise about future steering, collisions or hooks.
            boolean water=shot.level().getFluidState(BlockPos.containing(at)).is(net.minecraft.tags.FluidTags.WATER);
            if(thrown)velocity=velocity.add(0,-shot.getGravity(),0).scale(water?.8:.99);
            else if(fireball){var fire=(AbstractHurtingProjectile)shot;double inertia=water?.8:shot instanceof WitherSkull skull&&skull.isDangerous()?.73:.95;velocity=velocity.add(velocity.normalize().scale(fire.accelerationPower)).scale(inertia);}
            var end=at.add(velocity);if(!shot.level().hasChunkAt(BlockPos.containing(end)))break;
            var clip=shot.level().clip(new ClipContext(at,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,shot));
            boolean blocked=known&&clip.getType()!=HitResult.Type.MISS;if(blocked)end=clip.getLocation();
            double blast=shot instanceof CombatFireballAccess fire?fire.divzero$explosionPower()*2:shot instanceof WitherSkull?2:0;
            boolean potion=shot instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
            if(potion){var contents=((net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion)shot).getItem().getOrDefault(DataComponents.POTION_CONTENTS,PotionContents.EMPTY);for(var effect:contents.getAllEffects())if(!effect.getEffect().value().isBeneficial()){blast=4;break;}}
            if(blast>0){
                // Impact can occur on an entity as well as a wall. Other bodies are current observations, not future input.
                final var access=shot instanceof CombatProjectileAccess hit?hit:null;
                var entityHit=ProjectileUtil.getEntityHitResult(shot.level(),shot,at,end,shot.getBoundingBox().move(at.subtract(shot.position())).expandTowards(end.subtract(at)).inflate(1),e->e!=actor&&access!=null&&access.divzero$canHit(e));
                if(entityHit!=null){end=entityHit.getLocation();blocked=true;}
                if(blocked){var bounds=AABB.ofSize(end,blast*2,potion?4:blast*2,blast*2).inflate(potion?shot.getBbWidth()/2:0);
                    impacts.add(new Slice(tick,bounds,bounds,potion?Shape.BOX:Shape.RADIAL,end,blast));}
            }
            double margin=Math.max(0,Math.min(.3,(shot.tickCount+tick-2)/20d));
            // Unknown/homing paths widen with time and are re-observed on the next local tick.
            double uncertainty=known?0:Math.min(1.5,tick*.08);
            var fromBox=AABB.ofSize(at,2*(margin+uncertainty),2*(margin+uncertainty),2*(margin+uncertainty));
            slices.add(Slice.box(tick,fromBox,fromBox.move(end.subtract(at))));
            if(blocked)break;
            at=end;if(arrow)velocity=velocity.scale(water&&shot instanceof CombatArrowStateAccess arrowState?arrowState.divzero$waterInertia():.99).add(0,-shot.getGravity(),0);
        }
        values.add(new Attack(shot.getUUID()+"/flight",shot.getUUID(),shot.getOwner()==null?null:shot.getOwner().getUUID(),"PROJECTILE",known?"NATIVE_SWEEP_BALLISTIC_FORECAST":"UNKNOWN_MOTION_CONSERVATIVE_SWEEP",true,36,slices));
        if(!impacts.isEmpty())values.add(new Attack(shot.getUUID()+"/impact",shot.getUUID(),shot.getOwner()==null?null:shot.getOwner().getUUID(),"IMPACT_AREA","NATIVE_RADIUS_FORECAST_IMPACT",true,48,impacts));
    }
    /** Evaluate the body between two positions at one specific future tick. Expired attacks are not charged again. */
    double risk(SkillWork work,Vec3 from,Vec3 to,int tick){observe(work);return risk(work.player(),observation,from,to,tick);}
    public static double risk(LivingEntity actor,Observation observation,Vec3 from,Vec3 to,int tick){
        double result=0;var body=actor.getBoundingBox();var first=body.move(from.subtract(actor.position()));var last=body.move(to.subtract(actor.position()));
        for(var attack:observation.attacks())for(var slice:attack.slices())if(slice.tick()==tick&&intersects(actor,first,last,from,to,slice)){result+=attack.cost();break;}
        return result;
    }
    private static boolean intersects(LivingEntity actor,AABB from,AABB to,Vec3 fromFeet,Vec3 toFeet,Slice slice){
        if(slice.shape()==Shape.TARGET_LOCK){
            // A targeted beam follows its target; moving outside the old rendered beam is not a dodge.
            var eye=toFeet.add(0,actor.getEyeHeight(),0);return actor.level().clip(new ClipContext(slice.center(),eye,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,actor)).getType()==HitResult.Type.MISS;
        }
        if(!DamageSweep.intersects(box(from),box(to),box(slice.from()),box(slice.to())))return false;
        if(slice.shape()==Shape.CYLINDER){
            var delta=toFeet.subtract(fromFeet).multiply(1,0,1);var relative=slice.center().subtract(fromFeet).multiply(1,0,1);
            double t=Math.clamp(relative.dot(delta)/Math.max(1e-9,delta.lengthSqr()),0,1);return relative.subtract(delta.scale(t)).lengthSqr()<=slice.radius()*slice.radius();
        }
        if(slice.shape()==Shape.RADIAL){var delta=toFeet.subtract(fromFeet);var relative=slice.center().subtract(fromFeet);double t=Math.clamp(relative.dot(delta)/Math.max(1e-9,delta.lengthSqr()),0,1);return relative.subtract(delta.scale(t)).lengthSqr()<=slice.radius()*slice.radius();}
        return true;
    }
    double standingRisk(SkillWork work,Vec3 at,int horizon){double worst=0;for(int t=1;t<=Math.min(HORIZON,horizon);t++)worst=Math.max(worst,risk(work,at,at,t));return worst;}
    double routeRisk(SkillWork work,Vec3 end,int duration,int hold){
        var start=work.player().position();double worst=0;Vec3 previous=start;
        for(int t=1;t<=Math.min(HORIZON,duration+hold);t++){var at=start.lerp(end,Math.min(1,t/(double)Math.max(1,duration)));worst=Math.max(worst,risk(work,previous,at,t));previous=at;}return worst;
    }
    public Observation view(){return observation;}
    private static DamageSweep.Box box(AABB box){return new DamageSweep.Box((box.minX+box.maxX)/2,(box.minY+box.maxY)/2,(box.minZ+box.maxZ)/2,(box.maxX-box.minX)/2,(box.maxY-box.minY)/2,(box.maxZ-box.minZ)/2);}
}
