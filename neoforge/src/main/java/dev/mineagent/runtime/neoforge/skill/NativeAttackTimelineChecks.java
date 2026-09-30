package dev.mineagent.runtime.neoforge.skill;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.effect.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import java.util.*;

/** Deterministic native geometry/contract checks, strictly confined to the isolated smoke world. */
public final class NativeAttackTimelineChecks {
    public static Map<String,Object> run(ServerPlayer actor){
        if(!Boolean.getBoolean("mineagent.skillSmoke")||!System.getProperty("mineagent.skillSmokeMode","").equals("multi_attack"))throw new SecurityException("ISOLATED_ATTACK_TEST_ONLY");
        var level=actor.level();var spawned=new ArrayList<Entity>();var at=actor.position();var results=new LinkedHashMap<String,Object>();
        try{
            var source=EntityType.ZOMBIE.create(level,EntitySpawnReason.COMMAND);source.setPos(at.add(12,0,0));source.setNoAi(true);level.addFreshEntity(source);spawned.add(source);
            var arrow=EntityType.ARROW.create(level,EntitySpawnReason.COMMAND);arrow.setPos(at.add(-4,1,0));arrow.setOwner(source);arrow.setDeltaMovement(8,0,0);arrow.tickCount=20;level.addFreshEntity(arrow);spawned.add(arrow);
            var read=NativeAttackTimeline.inspect(actor,24);require(NativeAttackTimeline.risk(actor,read,at,at,1)>0,"FAST_ARROW_SWEEP_MISSED");results.put("fastArrowSweptHit",true);
            var wall=BlockPos.containing(at.add(-2,1,0));var old=level.getBlockState(wall);try{level.setBlock(wall,Blocks.STONE.defaultBlockState(),3);read=NativeAttackTimeline.inspect(actor,24);require(NativeAttackTimeline.risk(actor,read,at,at,1)==0,"ARROW_PASSED_SOLID_WALL");results.put("wallStopsProjectile",true);}finally{level.setBlock(wall,old,3);}
            var fang=new EvokerFangs(level,at.x,at.y,at.z,0,0,source);level.addFreshEntity(fang);spawned.add(fang);
            var cloud=new AreaEffectCloud(level,at.x,at.y,at.z);cloud.setOwner(source);cloud.setRadius(1.5f);cloud.setWaitTime(0);cloud.addEffect(new MobEffectInstance(MobEffects.POISON,100));level.addFreshEntity(cloud);spawned.add(cloud);
            read=NativeAttackTimeline.inspect(actor,24);var owned=read.attacks().stream().filter(a->source.getUUID().equals(a.owner())).map(NativeAttackTimeline.Attack::channel).distinct().toList();require(owned.containsAll(List.of("PROJECTILE","GROUND_STRIKE","PERSISTENT_AREA")),"CONCURRENT_CHANNELS_MISSING_"+owned);results.put("oneOwnerConcurrentChannels",owned);
            require(NativeAttackTimeline.risk(actor,read,at,at,8)>0,"FANG_STRIKE_TIME_MISSING");
            arrow.discard();cloud.discard();source.discard();read=NativeAttackTimeline.inspect(actor,24);require(read.attacks().stream().anyMatch(a->a.source().equals(fang.getUUID())),"RELEASED_ATTACK_REMOVED_WITH_OWNER");require(NativeAttackTimeline.risk(actor,read,at,at,9)==0,"EXPIRED_ATTACK_STILL_CHARGED");require(NativeAttackTimeline.risk(actor,read,at.add(2,0,0),at.add(2,0,0),8)==0,"FANG_ESCAPE_ENDPOINT_MISREAD");results.put("releasedAttackIndependentOfOwner",true);results.put("strikeTickAndExpiry",true);fang.discard();
            var beneficial=new AreaEffectCloud(level,at.x,at.y,at.z);beneficial.setWaitTime(0);beneficial.addEffect(new MobEffectInstance(MobEffects.REGENERATION,100));level.addFreshEntity(beneficial);spawned.add(beneficial);require(NativeAttackTimeline.inspect(actor,24).attacks().isEmpty(),"BENEFICIAL_CLOUD_TREATED_AS_DAMAGE");beneficial.discard();results.put("beneficialAreaIgnored",true);
            var fixture=EntityType.ARMOR_STAND.create(level,EntitySpawnReason.COMMAND);fixture.setPos(at.add(4,0,0));level.addFreshEntity(fixture);spawned.add(fixture);
            NativeAttackTimeline.Adapter adapter=new NativeAttackTimeline.Adapter(){public boolean supports(Entity e){return e==fixture;}public List<NativeAttackTimeline.Attack> observe(Entity e,LivingEntity observer,int horizon){var b=actor.getBoundingBox();return List.of(new NativeAttackTimeline.Attack(e.getUUID()+"/first",e.getUUID(),null,"MOD_FIRST","ADAPTER_NATIVE",true,20,List.of(NativeAttackTimeline.Slice.box(3,b,b))),new NativeAttackTimeline.Attack(e.getUUID()+"/second",e.getUUID(),null,"MOD_SECOND","ADAPTER_NATIVE",true,30,List.of(NativeAttackTimeline.Slice.box(3,b,b))));}};
            NativeAttackTimeline.register(adapter);try{read=NativeAttackTimeline.inspect(actor,24);require(NativeAttackTimeline.risk(actor,read,at,at,3)==50,"ADAPTER_CHANNELS_OVERWROTE_EACH_OTHER");require(NativeAttackTimeline.risk(actor,read,at,at,4)==0,"ADAPTER_WINDOW_EXPIRED");results.put("independentModChannels",true);}finally{NativeAttackTimeline.unregister(adapter);}
            var bounds=actor.getBoundingBox().inflate(20);var origin=fixture.position().add(0,actor.getEyeHeight(),0);
            var beam=new NativeAttackTimeline.Attack(fixture.getUUID()+"/lock",fixture.getUUID(),null,"TARGET_LOCK","TEST_NATIVE_SIGHT_CONTRACT",false,42,List.of(new NativeAttackTimeline.Slice(4,bounds,bounds,NativeAttackTimeline.Shape.TARGET_LOCK,origin,0)));
            var beamView=new NativeAttackTimeline.Observation(level.getGameTime(),List.of(beam),List.of());
            require(NativeAttackTimeline.risk(actor,beamView,at,at.add(0,0,1),4)>0,"TARGET_LOCK_FALSE_STRAFE_ESCAPE");
            var cover=BlockPos.containing(at.add(2,actor.getEyeHeight(),0));var prior=level.getBlockState(cover);try{level.setBlock(cover,Blocks.STONE.defaultBlockState(),3);require(NativeAttackTimeline.risk(actor,beamView,at,at,4)==0,"TARGET_LOCK_COVER_IGNORED");results.put("targetLockRequiresCover",true);}finally{level.setBlock(cover,prior,3);}
            var offhand=actor.getOffhandItem().copy();try{actor.setItemSlot(EquipmentSlot.OFFHAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CROSSBOW));var state=NativeCombatStates.read(actor,source);require(state.attacks().stream().anyMatch(a->a.source().contains("OFF_HAND")&&a.kind().equals("RANGED")&&a.running()),"OFFHAND_ATTACK_CHANNEL_MISSING");require(state.attacks().stream().anyMatch(a->a.source().equals("Player.availablePartialAttack")&&a.cooldownTicks()==0),"PLAYER_COOLDOWN_WRONGLY_PROHIBITS_ATTACK");results.put("playerOffhandAndWeakEarlyAttack",true);}finally{actor.setItemSlot(EquipmentSlot.OFFHAND,offhand);}
            return results;
        }finally{spawned.forEach(Entity::discard);}
    }
    private static void require(boolean value,String error){if(!value)throw new IllegalStateException(error);}
    private NativeAttackTimelineChecks(){}
}
