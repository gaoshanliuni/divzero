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
    public record Spell(String source,String kind,boolean preparing,int warmupGoalTicks,int releaseInTicks,int nextCastInTicks,int castingTicks,
                        Vec3 aimedAt,Vec3 direction,String directionKnowledge){}
    public record Snapshot(UUID entity,long observedTick,String type,UUID target,List<Attack> attacks,
                           List<Restriction> restrictions,String restrictionKnowledge,
                           int hurtAnimationTicks,int damageProtectionTicks,Vec3 velocity,Vec3 velocityChange,long velocitySampleTicks,double movementSpeed,
                           boolean usingItem,int useTicks,boolean inNativeMeleeRange,Vec3 lookDirection,float yaw,float pitch,List<Spell> spells) {
        public boolean meleeRestricted(){return restrictions.stream().anyMatch(r->r.remainingTicks>0&&r.blocksMelee);}
        public boolean areaAttack(){return attacks.stream().anyMatch(a->a.kind.equals("AREA")&&a.running);}
        public boolean ranged(){return attacks.stream().anyMatch(a->a.kind.equals("RANGED"));}
        public int openingTicks(long now){return dev.mineagent.runtime.core.task.CombatOpening.availableTicks(attacks.stream().map(a->new dev.mineagent.runtime.core.task.CombatOpening.Attack(a.kind,a.cooldownTicks,a.running)).toList(),restrictions.stream().map(r->new dev.mineagent.runtime.core.task.CombatOpening.Restriction(r.remainingTicks,r.blocksMelee,r.blocksRanged)).toList(),(int)Math.max(0,now-observedTick));}
    }
    private static final List<Adapter> ADAPTERS=new CopyOnWriteArrayList<>();
    private record Motion(long tick,Vec3 velocity,Vec3 change,long elapsed){}
    private static final Map<LivingEntity,Motion> MOTION=new WeakHashMap<>();
    public static void register(Adapter adapter){ADAPTERS.add(Objects.requireNonNull(adapter));}
    public static Snapshot read(LivingEntity enemy,LivingEntity observer){
        if(enemy.level().getServer()==null||!enemy.level().getServer().isSameThread()||enemy.level()!=observer.level())throw new IllegalStateException("COMBAT_OBSERVATION_CONTEXT");
        var attacks=new ArrayList<Attack>();var spells=new ArrayList<Spell>();var restrictions=new ArrayList<Restriction>();String knowledge="UNKNOWN_NO_ADAPTER";
        if(enemy instanceof Mob mob){
            for(var wrapped:mob.goalSelector.getAvailableGoals()){
                var goal=wrapped.getGoal();boolean running=wrapped.isRunning();boolean known=goal.getClass().getName().startsWith("net.minecraft.");
                if(goal instanceof CombatSpellGoalAccess cast&&enemy instanceof net.minecraft.world.entity.monster.illager.Evoker&&known){
                    String kind=goal.getClass().getSimpleName().contains("AttackSpell")?"FANGS":goal.getClass().getSimpleName().contains("Summon")?"SUMMON_VEX":"OTHER";
                    boolean preparing=running&&cast.divzero$warmup()>0;var aim=mob.getTarget()==null?enemy.position().add(enemy.getLookAngle()):mob.getTarget().position();
                    int release=preparing?(goal.requiresUpdateEveryTick()?cast.divzero$warmup():Math.max(1,cast.divzero$warmup()*2-Math.floorMod(enemy.tickCount+enemy.getId(),2))):-1;int next=Math.max(0,cast.divzero$nextCastTick()-enemy.tickCount);
                    spells.add(new Spell(goal.getClass().getName(),kind,preparing,cast.divzero$warmup(),release,next,enemy instanceof CombatSpellcasterAccess state?state.divzero$castingTicks():-1,aim,aim.subtract(enemy.position()).normalize(),"TRACKING_TARGET_UNTIL_RELEASE"));
                    int casting=enemy instanceof CombatSpellcasterAccess state?state.divzero$castingTicks():0;
                    int earliest=preparing?release:Math.max(next,casting)+Math.max(0,cast.divzero$warmupDuration()-2);
                    if(!kind.equals("OTHER"))attacks.add(new Attack(goal.getClass().getName(),kind.equals("FANGS")?"GROUND_SPELL":"SUMMON",earliest,0,kind.equals("FANGS")?20:16,mob.getTarget()!=null));
                }
                if(goal instanceof CombatMeleeGoalAccess access){var range=mob.getActiveItem().get(DataComponents.ATTACK_RANGE);attacks.add(new Attack(goal.getClass().getName(),"MELEE",running&&known?access.divzero$attackCooldown():-1,range==null?0:range.effectiveMinRange(mob),range==null?CombatMobRangeAccess.divzero$defaultReach():range.effectiveMaxRange(mob),running));}
                if(goal instanceof CombatRangedGoalAccess access)attacks.add(new Attack(goal.getClass().getName(),"RANGED",running&&known?access.divzero$attackCooldown():-1,0,access.divzero$attackRadius(),running));
                if(goal instanceof CombatBowGoalAccess access)attacks.add(new Attack(goal.getClass().getName(),"RANGED",running&&known?access.divzero$attackCooldown():-1,0,Math.sqrt(access.divzero$attackRadiusSquared()),running));
                if(goal instanceof CombatCrossbowGoalAccess access){
                    var stack=enemy.isUsingItem()?enemy.getUseItem():enemy.getMainHandItem();int cooldown=access.divzero$attackDelay();
                    // The native goal always waits at least 20 more goal ticks after loading.
                    if(cooldown<=0&&stack.getItem() instanceof net.minecraft.world.item.CrossbowItem&&stack.getOrDefault(DataComponents.CHARGED_PROJECTILES,net.minecraft.world.item.component.ChargedProjectiles.EMPTY).isEmpty())cooldown=Math.max(0,net.minecraft.world.item.CrossbowItem.getChargeDuration(stack,enemy)-(enemy.isUsingItem()?enemy.getTicksUsingItem():0))+20;
                    attacks.add(new Attack(goal.getClass().getName()+".chargeOrDelay","RANGED",running&&known?cooldown:-1,0,Math.sqrt(access.divzero$attackRadiusSquared()),running));
                }
                if(goal instanceof net.minecraft.world.entity.ai.goal.SpearUseGoal<?>){var range=enemy.getAttackRangeWith(enemy.getMainHandItem());attacks.add(new Attack(goal.getClass().getName()+".startupOrContactCooldown","MELEE",running&&known?kineticDelay(enemy,observer):-1,range.effectiveMinRange(enemy),range.effectiveMaxRange(enemy),running));}
            }
            // These exact native types have no general stun state. Subclasses remain unknown.
            if(Set.of("net.minecraft.world.entity.monster.zombie.Zombie","net.minecraft.world.entity.monster.skeleton.Skeleton","net.minecraft.world.entity.monster.Zombie","net.minecraft.world.entity.monster.Skeleton").contains(enemy.getClass().getName()))knowledge="NATIVE_NO_STUN";
        }
        if(enemy instanceof net.minecraft.world.entity.player.Player player){
            var range=player.getAttackRangeWith(player.getMainHandItem());double interval=20/Math.max(.001,player.getAttributeValue(Attributes.ATTACK_SPEED));
            int remaining=(int)Math.ceil((1-player.getAttackStrengthScale(0))*interval);
            // Attack strength scales damage; it does not prohibit a human from making a weak early attack.
            attacks.add(new Attack("Player.nativeAttackStrength","FULL_STRENGTH",remaining,range.effectiveMinRange(player),range.effectiveMaxRange(player),false));
            attacks.add(new Attack("Player.availablePartialAttack","MELEE",0,range.effectiveMinRange(player),range.effectiveMaxRange(player),true));
            for(var hand:net.minecraft.world.InteractionHand.values()){
                var stack=player.getItemInHand(hand);var item=stack.getItem();
                boolean ranged=item instanceof net.minecraft.world.item.ProjectileWeaponItem||item instanceof net.minecraft.world.item.TridentItem
                        ||item instanceof net.minecraft.world.item.ThrowablePotionItem||stack.is(net.minecraft.world.item.Items.SNOWBALL)||stack.is(net.minecraft.world.item.Items.EGG);
                if(ranged)attacks.add(new Attack("Player."+hand+".nativeRangedItem","RANGED",player.getCooldowns().isOnCooldown(stack)?-1:0,0,32,true));
                if(stack.has(DataComponents.KINETIC_WEAPON))attacks.add(new Attack("Player."+hand+".kineticContact","MELEE",kineticDelay(player,observer),range.effectiveMinRange(player),range.effectiveMaxRange(player),player.isUsingItem()&&player.getUsedItemHand()==hand));
            }
            knowledge="NATIVE_PLAYER_NO_GENERAL_STUN";
        }
        if(enemy instanceof Ravager ravager){knowledge="NATIVE_RAVAGER";restrictions.add(new Restriction("Ravager.stunnedTick",ravager.getStunnedTick(),true,true,false));restrictions.add(new Restriction("Ravager.roarTick",ravager.getRoarTick(),true,true,false));attacks.add(new Attack("Ravager.roarTick","AREA",ravager.getRoarTick()>10?ravager.getRoarTick()-10:0,0,4,ravager.getRoarTick()>10));}
        if(enemy instanceof net.minecraft.world.entity.monster.Vex vex){knowledge="NATIVE_VEX_CHARGE";attacks.add(new Attack("Vex.isCharging/contactBox","MELEE",vex.isCharging()?0:-1,0,vex.getBbWidth(),vex.isCharging()));}
        if(enemy instanceof net.minecraft.world.entity.monster.Creeper creeper&&creeper instanceof CombatCreeperAccess state)attacks.add(new Attack("Creeper.nativeFuse","AREA",creeper.getSwellDir()>0?Math.max(0,state.divzero$maxSwell()-state.divzero$swell()):-1,0,state.divzero$explosionRadius()*2*(creeper.isPowered()?2:1),creeper.getSwellDir()>0));
        for(var adapter:ADAPTERS)if(adapter.supports(enemy)){restrictions.addAll(adapter.restrictions(enemy));attacks.addAll(adapter.attacks(enemy));knowledge="ADAPTER";}
        long now=enemy.level().getGameTime();var motion=MOTION.get(enemy);if(motion==null||motion.tick!=now){motion=new Motion(now,enemy.getDeltaMovement(),motion==null?Vec3.ZERO:enemy.getDeltaMovement().subtract(motion.velocity),motion==null?0:now-motion.tick);MOTION.put(enemy,motion);}
        return new Snapshot(enemy.getUUID(),now,BuiltInRegistries.ENTITY_TYPE.getKey(enemy.getType()).toString(),enemy instanceof Mob mob&&mob.getTarget()!=null?mob.getTarget().getUUID():null,List.copyOf(attacks),List.copyOf(restrictions),knowledge,enemy.hurtTime,enemy.invulnerableTime,enemy.getDeltaMovement(),motion.change,motion.elapsed,enemy.getAttribute(Attributes.MOVEMENT_SPEED)==null?0:enemy.getAttributeValue(Attributes.MOVEMENT_SPEED),enemy.isUsingItem(),enemy.getTicksUsingItem(),meleeAt(enemy,observer,observer.position()),enemy.getLookAngle(),enemy.getYRot(),enemy.getXRot(),List.copyOf(spells));
    }
    /** Native startup and this observer's real spear contact timer, not general damage immunity. */
    private static int kineticDelay(LivingEntity enemy,LivingEntity observer){
        var stack=enemy.isUsingItem()?enemy.getUseItem():enemy.getMainHandItem();var weapon=stack.get(DataComponents.KINETIC_WEAPON);if(weapon==null)return -1;
        int startup=enemy.isUsingItem()?Math.max(0,weapon.delayTicks()-enemy.getTicksUsingItem()):weapon.delayTicks();
        int contact=0,limit=weapon.contactCooldownTicks();
        if(limit>0&&enemy.wasRecentlyStabbed(observer,limit)){
            int low=1,high=limit;while(low<high){int middle=low+(high-low)/2;if(enemy.wasRecentlyStabbed(observer,middle))high=middle;else low=middle+1;}contact=limit-low;
        }
        return Math.max(startup,contact);
    }
    /** Evaluate the actual mob attack hitbox at a possible observer position, without moving either entity. */
    /** These vanilla bodies use spells, projectiles or explosions, never Mob's inherited melee box. Unknown mods remain conservative. */
    public static boolean contactCapable(LivingEntity enemy){
        if(!enemy.getClass().getName().startsWith("net.minecraft."))return true;
        if(!Set.of("minecraft:evoker","minecraft:witch","minecraft:guardian","minecraft:elder_guardian","minecraft:ghast","minecraft:creeper").contains(BuiltInRegistries.ENTITY_TYPE.getKey(enemy.getType()).toString()))return true;
        // Added melee goals/adapters may deliberately give a vanilla caster new contact attacks.
        if(enemy instanceof Mob mob)for(var wrapped:mob.goalSelector.getAvailableGoals()){
            var goal=wrapped.getGoal();if(goal instanceof CombatMeleeGoalAccess||goal instanceof net.minecraft.world.entity.ai.goal.SpearUseGoal<?>||!goal.getClass().getName().startsWith("net.minecraft."))return true;
        }
        for(var adapter:ADAPTERS)if(adapter.supports(enemy)&&adapter.attacks(enemy).stream().anyMatch(a->a.kind().equals("MELEE")))return true;
        return false;
    }
    public static boolean meleeAt(LivingEntity enemy,LivingEntity actor,Vec3 position){
        if(!contactCapable(enemy))return false;
        if(enemy instanceof net.minecraft.world.entity.monster.Vex vex)return vex.isCharging()&&vex.getBoundingBox().intersects(actor.getBoundingBox().move(position.subtract(actor.position())));
        if(enemy instanceof net.minecraft.world.entity.player.Player player)return player.isWithinAttackRange(player.getMainHandItem(),actor.getHitbox().move(position.subtract(actor.position())),0);
        if(enemy instanceof Mob unknown&&!enemy.getClass().getName().startsWith("net.minecraft.")){return unknown.isWithinMeleeAttackRange(actor)||position.distanceToSqr(enemy.position())<=actor.distanceToSqr(enemy);}
        if(enemy instanceof Mob mob&&mob instanceof CombatMobRangeAccess access){var item=mob.getActiveItem().get(DataComponents.ATTACK_RANGE);double max=item==null?CombatMobRangeAccess.divzero$defaultReach():item.effectiveMaxRange(mob),min=item==null?0:item.effectiveMinRange(mob);var target=actor.getHitbox().move(position.subtract(actor.position()));return access.divzero$attackBox(max).intersects(target)&&(min<=0||!access.divzero$attackBox(min).intersects(target));}
        return enemy.getBoundingBox().inflate(2).intersects(actor.getDimensions(actor.getPose()).makeBoundingBox(position));
    }
    /** Short-horizon translation of the actual native attack box; prediction never becomes a stun claim. */
    public static boolean meleeAtAfter(LivingEntity enemy,LivingEntity actor,Vec3 position,int ticks){
        int horizon=Math.clamp(ticks,0,4);var velocity=enemy.getDeltaMovement();
        var drift=new Vec3(velocity.x*horizon,0,velocity.z*horizon);if(drift.lengthSqr()>4)drift=drift.normalize().scale(2);
        return meleeAt(enemy,actor,position.subtract(drift));
    }
    /** Geometry and visibility are separate. Ordinary native melee cannot damage through a solid wall. */
    public static boolean meleeVisibleAt(LivingEntity enemy,LivingEntity actor,Vec3 position,Vec3 sourceDrift){
        if(enemy instanceof net.minecraft.world.entity.monster.Vex||!enemy.getClass().getName().startsWith("net.minecraft.")&&!(enemy instanceof net.minecraft.world.entity.player.Player))return true;
        return enemy.level().clip(new net.minecraft.world.level.ClipContext(enemy.getEyePosition().add(sourceDrift),position.add(0,actor.getEyeHeight(),0),net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,enemy)).getType()==HitResult.Type.MISS;
    }
    private NativeCombatStates(){}
}
