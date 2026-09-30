package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.*;

/** Explicit, server-authorized enhancement branch. Preferences outlive every transient strike. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class BoostRuntime {
    private record Strike(ServerPlayer body,UUID target,UUID operation,int tick,double floor,boolean hopped){}
    private static final Map<MinecraftServer,Map<UUID,Strike>> STRIKES=new IdentityHashMap<>();
    public static void arm(ServerPlayer body,LivingEntity target,UUID operation){
        if(!ActorEnhancements.boost(body)||!target.isAlive()||body.getAttackStrengthScale(.5f)<.95f||!body.isWithinAttackRange(body.getMainHandItem(),target.getHitbox(),0)||!body.hasLineOfSight(target))return;
        var values=STRIKES.computeIfAbsent(body.level().getServer(),s->new HashMap<>());var old=values.get(body.getUUID());
        if(old!=null&&old.operation.equals(operation))return;
        boolean hopped=false;double floor=body.getY();var settings=ActorEnhancements.forBody(body);
        if(body instanceof MineAgentPlayer&&settings.microHop()&&body.onGround()&&!body.isInWater()&&!body.isPassenger()
                &&body.level().noCollision(body,body.getBoundingBox().expandTowards(0,.25,0))){
            body.setPos(body.getX(),body.getY()+.25,body.getZ());body.setOnGround(false);hopped=true;
        }
        values.put(body.getUUID(),new Strike(body,target.getUUID(),operation,body.level().getServer().getTickCount(),floor,hopped));
    }
    @SubscribeEvent public static void critical(net.neoforged.neoforge.event.entity.player.CriticalHitEvent event){
        if(!(event.getEntity() instanceof ServerPlayer body)||!ActorEnhancements.boost(body)||!ActorEnhancements.forBody(body).enhancedCritical())return;
        var values=STRIKES.get(body.level().getServer());var strike=values==null?null:values.get(body.getUUID());
        if(strike==null||strike.body!=body||!strike.target.equals(event.getTarget().getUUID())||body.level().getServer().getTickCount()-strike.tick>10)return;
        if(body.isInWater()||body.isPassenger()||body.onClimbable()||body.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS))return;
        event.setCriticalHit(true);event.setDamageMultiplier(Math.max(event.getDamageMultiplier(),1.5f));
    }
    /** Scale only the observed knockback impulse, keeping the actor's pre-existing movement. */
    public static void knockback(LivingEntity entity,Vec3 before){
        if(!(entity instanceof ServerPlayer body)||!ActorEnhancements.boost(body))return;
        var settings=ActorEnhancements.forBody(body);var impulse=body.getDeltaMovement().subtract(before);
        body.setDeltaMovement(before.add(impulse.x*settings.horizontalKnockback(),impulse.y*settings.verticalKnockback(),impulse.z*settings.horizontalKnockback()));
    }
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        var values=STRIKES.get(event.getServer());if(values==null)return;
        for(var iterator=values.values().iterator();iterator.hasNext();){var strike=iterator.next();var body=strike.body;int age=event.getServer().getTickCount()-strike.tick;
            if(!ActorEnhancements.boost(body)){iterator.remove();continue;}
            if(strike.hopped&&age==2){double drop=strike.floor-body.getY();var box=body.getBoundingBox().move(0,drop,0);
                if(Math.abs(drop)<.6&&body.level().noCollision(body,box)&&!body.level().noCollision(body,box.move(0,-.05,0)))body.setPos(body.getX(),strike.floor,body.getZ());
            }
            if(age>10)iterator.remove();
        }
    }
    @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppingEvent event){STRIKES.remove(event.getServer());}
    private BoostRuntime(){}
}
