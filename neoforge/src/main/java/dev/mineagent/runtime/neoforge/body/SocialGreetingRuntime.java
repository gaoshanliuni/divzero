package dev.mineagent.runtime.neoforge.body;

import dev.mineagent.runtime.core.agent.GreetingGesture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/** A short cosmetic interaction; movement intent stays intact and danger immediately wins. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class SocialGreetingRuntime {
    private static final Map<MinecraftServer,State> STATES=new WeakHashMap<>();
    private static final class State {final Map<UUID,GreetingGesture> gestures=new HashMap<>();final Map<UUID,Reply> replies=new HashMap<>();final Map<UUID,Integer> cooldown=new HashMap<>();final Map<UUID,Map<String,Object>> last=new HashMap<>();}
    private static final class Reply {ServerPlayer target;final int started;final boolean sprint;int crouches;boolean down;Reply(ServerPlayer target,int tick,boolean sprint){this.target=target;started=tick;this.sprint=sprint;}}
    @SubscribeEvent public static void observe(ServerTickEvent.Pre event){
        var server=event.getServer();if(!dev.mineagent.runtime.neoforge.WorldIdentityRuntime.ready(server))return;var state=STATES.computeIfAbsent(server,k->new State());int tick=server.getTickCount();
        for(var p:server.getPlayerList().getPlayers())if(!(p instanceof MineAgentPlayer)&&p.isAlive()&&!p.isSpectator())state.gestures.computeIfAbsent(p.getUUID(),k->new GreetingGesture()).observe(tick,p.isShiftKeyDown());
        if(tick%200==0){state.gestures.keySet().removeIf(id->server.getPlayerList().getPlayer(id)==null);state.cooldown.entrySet().removeIf(e->e.getValue()<tick-200);state.last.keySet().removeIf(id->server.getPlayerList().getPlayer(id)==null);state.replies.entrySet().removeIf(e->server.getPlayerList().getPlayer(e.getKey())==null);}
    }
    public static boolean active(MineAgentPlayer body){var state=STATES.get(body.level().getServer());return state!=null&&state.replies.containsKey(body.getUUID());}
    public static Map<String,Object> observation(MineAgentPlayer body){var state=STATES.get(body.level().getServer());if(state==null)return Map.of("active",false);var reply=state.replies.get(body.getUUID());return reply==null?state.last.getOrDefault(body.getUUID(),Map.of("active",false)):Map.of("active",true,"player",reply.target.getUUID(),"started",reply.started,"crouches",reply.crouches);}
    public static boolean apply(MineAgentPlayer body){
        var server=body.level().getServer();var state=STATES.get(server);if(state==null)return false;int tick=server.getTickCount();var reply=state.replies.get(body.getUUID());
        if(reply==null&&tick<state.cooldown.getOrDefault(body.getUUID(),0))return false;
        var candidates=new ArrayList<ServerPlayer>();
        for(var player:server.getPlayerList().getPlayers())if(!(player instanceof MineAgentPlayer)&&player.isAlive()&&!player.isSpectator()&&player.level()==body.level()&&body.distanceToSqr(player)<=144){var signal=state.gestures.get(player.getUUID());if(signal!=null&&signal.active(tick)){var direction=body.getEyePosition().subtract(player.getEyePosition());if(direction.lengthSqr()<1e-6||player.getLookAngle().dot(direction.normalize())>=.9&&player.hasLineOfSight(body))candidates.add(player);}}
        if(reply==null&&candidates.isEmpty())return false;
        if(unsafe(body)){finish(body,state,"DANGER_OR_NATIVE_ACTION",false);return false;}
        candidates.sort(Comparator.<ServerPlayer>comparingDouble(body::distanceToSqr).thenComparing(p->p.getUUID().toString()));
        if(reply==null){reply=new Reply(candidates.getFirst(),tick,body.isSprinting());state.replies.put(body.getUUID(),reply);}
        else if(!candidates.isEmpty())reply.target=candidates.getFirst();
        if(tick-reply.started>=48||reply.target.level()!=body.level()||!reply.target.isAlive()||server.getPlayerList().getPlayer(reply.target.getUUID())!=reply.target||body.distanceToSqr(reply.target)>196){finish(body,state,"COMPLETED",true);return false;}
        boolean crouch=GreetingGesture.replyCrouch(tick-reply.started);if(crouch&&!reply.down)reply.crouches++;reply.down=crouch;
        body.applyGreetingPose(crouch);body.setSprinting(false);body.setJumping(false);body.setDeltaMovement(0,body.getDeltaMovement().y,0);
        body.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,reply.target.getEyePosition());return true;
    }
    private static boolean unsafe(MineAgentPlayer body){
        if(!body.canAct()||body.nativePvpMovement()||!body.onGround()||body.isPassenger()||body.isInWater()||body.isInLava()||body.isOnFire()||body.isUsingItem()||body.miningOperation()!=null||body.requestedSneaking()||!NativeTravelPolicy.supported(body,body.position(),.1)||dev.mineagent.runtime.neoforge.skill.SkillRuntime.socialActionBusy(body))return true;
        if(body.getLastHurtByMob()!=null&&body.tickCount-body.getLastHurtByMobTimestamp()<100)return true;
        var owner=body.level().getServer().getPlayerList().getPlayer(body.ownerPlayerId());
        for(var entity:body.level().getEntities(body,body.getBoundingBox().inflate(12),e->e.isAlive())){
            if(entity instanceof Mob mob&&(mob.getTarget()==body||owner!=null&&mob.getTarget()==owner||mob instanceof net.minecraft.world.entity.monster.Monster&&body.distanceToSqr(mob)<36&&body.hasLineOfSight(mob)))return true;
            if(entity instanceof net.minecraft.world.entity.projectile.Projectile projectile&&projectile.getOwner()!=body&&projectile.getDeltaMovement().lengthSqr()>.01&&body.distanceToSqr(projectile)<64)return true;
        }return false;
    }
    private static void finish(MineAgentPlayer body,State state,String reason,boolean complete){var reply=state.replies.remove(body.getUUID());if(reply==null)return;body.applyGreetingPose(false);if(complete&&reply.sprint&&NativeTravelPolicy.canSprint(body)&&!body.requestedSneaking())body.setSprinting(true);int tick=body.level().getServer().getTickCount();state.cooldown.put(body.getUUID(),tick+120);state.last.put(body.getUUID(),Map.of("active",false,"player",reply.target.getUUID(),"crouches",reply.crouches,"reason",reason,"completed",complete,"finished",tick));}
    private SocialGreetingRuntime(){}
}
