package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.neoforge.mixin.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.phys.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Read-only adapters. Hurt animation, damage immunity and velocity never imply stun. */
public final class NativeCombatStates {
    public record Restriction(String source,int remainingTicks,boolean blocksMovement,boolean blocksMelee,boolean blocksRanged){}
    public interface Adapter {
        boolean supports(LivingEntity entity);
        List<Restriction> restrictions(LivingEntity entity);
        default List<Attack> attacks(LivingEntity entity){return List.of();}
    }
    public record Attack(String source,String kind,int cooldownTicks,double minRange,double maxRange,boolean running){}
    public record Snapshot(UUID entity,long observedTick,String type,UUID target,List<Attack> attacks,
                           List<Restriction> restrictions,String restrictionKnowledge,
                           int hurtAnimationTicks,int damageProtectionTicks,Vec3 velocity,Vec3 velocityChange,long velocitySampleTicks,double movementSpeed,
                           boolean usingItem,int useTicks,boolean inNativeMeleeRange) {
        public boolean meleeRestricted(){return restrictions.stream().anyMatch(r->r.remainingTicks>0&&r.blocksMelee);}
        public boolean areaAttack(){return attacks.stream().anyMatch(a->a.kind.equals("AREA")&&a.running);}
        public boolean ranged(){return attacks.stream().anyMatch(a->a.kind.equals("RANGED"));}
    }
    private static final List<Adapter> ADAPTERS=new CopyOnWriteArrayList<>();
    private record Motion(long tick,Vec3 velocity,Vec3 change,long elapsed){}
    private static final Map<LivingEntity,Motion> MOTION=new WeakHashMap<>();
    public static void register(Adapter adapter){ADAPTERS.add(Objects.requireNonNull(adapter));}
    public static Snapshot read(LivingEntity enemy,LivingEntity observer){
        if(enemy.level().getServer()==null||!enemy.level().getServer().isSameThread()||enemy.level()!=observer.level())throw new IllegalStateException("COMBAT_OBSERVATION_CONTEXT");
        var attacks=new ArrayList<Attack>();var restrictions=new ArrayList<Restriction>();String knowledge="UNKNOWN_NO_ADAPTER";
        if(enemy instanceof Mob mob){
            for(var wrapped:mob.goalSelector.getAvailableGoals()){
                var goal=wrapped.getGoal();boolean running=wrapped.isRunning();boolean known=goal.getClass().getName().startsWith("net.minecraft.");
                if(goal instanceof CombatMeleeGoalAccess access){var range=mob.getActiveItem().get(DataComponents.ATTACK_RANGE);attacks.add(new Attack(goal.getClass().getName(),"MELEE",running&&known?access.divzero$attackCooldown():-1,range==null?0:range.effectiveMinRange(mob),range==null?CombatMobRangeAccess.divzero$defaultReach():range.effectiveMaxRange(mob),running));}
                if(goal instanceof CombatRangedGoalAccess access)attacks.add(new Attack(goal.getClass().getName(),"RANGED",running&&known?access.divzero$attackCooldown():-1,0,access.divzero$attackRadius(),running));
                if(goal instanceof CombatBowGoalAccess access)attacks.add(new Attack(goal.getClass().getName(),"RANGED",running&&known?access.divzero$attackCooldown():-1,0,Math.sqrt(access.divzero$attackRadiusSquared()),running));
            }
            // These exact native types have no general stun state. Subclasses remain unknown.
            if(Set.of("net.minecraft.world.entity.monster.zombie.Zombie","net.minecraft.world.entity.monster.skeleton.Skeleton","net.minecraft.world.entity.monster.Zombie","net.minecraft.world.entity.monster.Skeleton").contains(enemy.getClass().getName()))knowledge="NATIVE_NO_STUN";
        }
        if(enemy instanceof Ravager ravager){knowledge="NATIVE_RAVAGER";restrictions.add(new Restriction("Ravager.stunnedTick",ravager.getStunnedTick(),true,true,false));restrictions.add(new Restriction("Ravager.roarTick",ravager.getRoarTick(),true,true,false));attacks.add(new Attack("Ravager.roarTick","AREA",ravager.getRoarTick()>10?ravager.getRoarTick()-10:0,0,4,ravager.getRoarTick()>0));}
        if(enemy instanceof net.minecraft.world.entity.monster.Creeper creeper&&creeper instanceof CombatCreeperAccess state)attacks.add(new Attack("Creeper.nativeFuse","AREA",creeper.getSwellDir()>0?Math.max(0,state.divzero$maxSwell()-state.divzero$swell()):-1,0,state.divzero$explosionRadius()*2*(creeper.isPowered()?2:1),creeper.getSwellDir()>0));
        for(var adapter:ADAPTERS)if(adapter.supports(enemy)){restrictions.addAll(adapter.restrictions(enemy));attacks.addAll(adapter.attacks(enemy));knowledge="ADAPTER";}
        long now=enemy.level().getGameTime();var motion=MOTION.get(enemy);if(motion==null||motion.tick!=now){motion=new Motion(now,enemy.getDeltaMovement(),motion==null?Vec3.ZERO:enemy.getDeltaMovement().subtract(motion.velocity),motion==null?0:now-motion.tick);MOTION.put(enemy,motion);}
        return new Snapshot(enemy.getUUID(),now,BuiltInRegistries.ENTITY_TYPE.getKey(enemy.getType()).toString(),enemy instanceof Mob mob&&mob.getTarget()!=null?mob.getTarget().getUUID():null,List.copyOf(attacks),List.copyOf(restrictions),knowledge,enemy.hurtTime,enemy.invulnerableTime,enemy.getDeltaMovement(),motion.change,motion.elapsed,enemy.getAttribute(Attributes.MOVEMENT_SPEED)==null?0:enemy.getAttributeValue(Attributes.MOVEMENT_SPEED),enemy.isUsingItem(),enemy.getTicksUsingItem(),enemy instanceof Mob mob&&mob.isWithinMeleeAttackRange(observer));
    }
    /** Evaluate the actual mob attack hitbox at a possible observer position, without moving either entity. */
    public static boolean meleeAt(LivingEntity enemy,LivingEntity actor,Vec3 position){
        if(enemy instanceof Mob unknown&&!enemy.getClass().getName().startsWith("net.minecraft.")){return unknown.isWithinMeleeAttackRange(actor)||position.distanceToSqr(enemy.position())<=actor.distanceToSqr(enemy);}
        if(enemy instanceof Mob mob&&mob instanceof CombatMobRangeAccess access){var item=mob.getActiveItem().get(DataComponents.ATTACK_RANGE);double max=item==null?CombatMobRangeAccess.divzero$defaultReach():item.effectiveMaxRange(mob),min=item==null?0:item.effectiveMinRange(mob);var target=actor.getHitbox().move(position.subtract(actor.position()));return access.divzero$attackBox(max).intersects(target)&&(min<=0||!access.divzero$attackBox(min).intersects(target));}
        return enemy.getBoundingBox().inflate(2).intersects(actor.getDimensions(actor.getPose()).makeBoundingBox(position));
    }
    private NativeCombatStates(){}
}
