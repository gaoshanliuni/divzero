package dev.mineagent.runtime.neoforge.skill;

import dev.mineagent.runtime.api.agent.BodyDomain;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.*;

/** Native bucket use, shared by AI bodies and already-authorized player input sessions. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class WaterClutchRuntime {
    private record Landing(BlockPos support,BlockPos source,Vec3 aim,double drop){}
    private static final Map<MinecraftServer,Map<UUID,Clutch>> ACTIVE=new IdentityHashMap<>();
    private static final Map<MinecraftServer,Map<UUID,Map<String,Object>>> RESULTS=new IdentityHashMap<>();
    private static final Map<MinecraftServer,Map<UUID,Integer>> RETRY=new IdentityHashMap<>();
    private WaterClutchRuntime(){}
    public static Map<String,Object> snapshot(ServerPlayer p){return RESULTS.getOrDefault(p.level().getServer(),Map.of()).getOrDefault(p.getUUID(),Map.of("state","IDLE"));}
    private static boolean allowed(ServerPlayer p){
        if(!p.isAlive()||p.isSpectator()||p.isCreative()||p.isPassenger())return false;
        if(p instanceof MineAgentPlayer ai){
            var server=p.level().getServer();var owner=server.getPlayerList().getPlayer(ai.ownerPlayerId());
            return ai.canAct()&&owner!=null&&ServerTaskStart.allowed(owner,ai.agentId())&&!Boolean.parseBoolean(MineAgentRuntimeServices.config(server).snapshot().values().getOrDefault("behavior."+MineAgentRuntimeServices.worldId(server)+"."+ai.agentId()+".explicitlyStopped","false"));
        }
        return dev.mineagent.runtime.neoforge.task.AutonomousPlayerAgent.emergencySession(p)!=null;
    }
    private static int slot(ServerPlayer p,Item item){for(int i=0;i<36;i++)if(p.getInventory().getItem(i).is(item))return i;return -1;}
    private static boolean hasWater(ServerPlayer p){return p.getOffhandItem().is(Items.WATER_BUCKET)||slot(p,Items.WATER_BUCKET)>=0;}
    private static Landing predict(ServerPlayer p){
        if(p.onGround()||p.isInWater()||p.isFallFlying()||p.getAbilities().flying||p.isIgnoringFallDamageFromCurrentImpulse()||p.getDeltaMovement().y>=-.12)return null;
        var position=p.position();var velocity=p.getDeltaMovement();
        for(int tick=0;tick<18;tick++){
            var next=position.add(velocity);if(!p.level().hasChunkAt(BlockPos.containing(next)))return null;
            var hit=p.level().clip(new ClipContext(position.add(0,.02,0),next,ClipContext.Block.COLLIDER,ClipContext.Fluid.ANY,p));
            if(hit.getType()==HitResult.Type.BLOCK){
                if(hit.getDirection()!=Direction.UP)return null;var support=hit.getBlockPos();var source=support.above();
                double drop=p.fallDistance+Math.max(0,p.getY()-hit.getLocation().y);
                if(drop<=p.getAttributeValue(Attributes.SAFE_FALL_DISTANCE)+.05||p.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER)<=0||!p.level().getBlockState(source).isAir()||!p.level().getFluidState(source).isEmpty())return null;
                if(p.level().environmentAttributes().getValue(net.minecraft.world.attribute.EnvironmentAttributes.WATER_EVAPORATES,source)||!p.level().mayInteract(p,support))return null;
                return new Landing(support,source,new Vec3(source.getX()+.5,hit.getLocation().y,source.getZ()+.5),drop);
            }
            position=next;velocity=new Vec3(velocity.x*.91,(velocity.y-.08)*.98,velocity.z*.91);
        }return null;
    }
    private static final class Clutch {
        final ServerPlayer player;final SkillActor actor;final UUID token=UUID.randomUUID();final Object level;final int originalSlot,started;final float healthBefore;
        Landing landing;boolean placed,offhand,completing;int restoreAt;int placedAt,settledAt=-1,lastUse=-100,tries;String outcome="PREDICTED";UUID action;
        Clutch(ServerPlayer player,Landing landing){this.player=player;this.level=player.level();this.landing=landing;originalSlot=player.getInventory().getSelectedSlot();started=player.level().getServer().getTickCount();healthBefore=player.getHealth();actor=player instanceof MineAgentPlayer ai?new AiSkillActor(ai):PlayerSkillActor.emergency(player);}
        boolean current(){return player.level()==level&&allowed(player)&&actor!=null&&actor.current();}
        boolean tick(){
            int now=player.level().getServer().getTickCount();if(!current()||now-started>100){outcome=placed?"RECOVERY_DEFERRED":"CANCELLED";return true;}
            if(completing){if(current()&&player.getInventory().getSelectedSlot()!=originalSlot&&now-restoreAt<6){actor.select(token,originalSlot);return false;}return true;}
            if(!actor.inputReady())return false;
            if(!actor.controls().acquire(token,EnumSet.allOf(BodyDomain.class),120,this::current,()->actor.stop(token)))return false;
            actor.haltMotion(token);
            if(!placed){
                if(action!=null&&(player.getMainHandItem().is(Items.BUCKET)||player.getOffhandItem().is(Items.BUCKET))&&player.level().getBlockState(landing.source).is(Blocks.WATER)&&player.level().getFluidState(landing.source).isSource()){
                    placed=true;placedAt=now;outcome="NATIVE_SOURCE_PLACED";record();return false;
                }
                if(player.onGround()||player.isInWater()){outcome="LANDING_WITHOUT_OWN_SOURCE";return true;}
                var predicted=predict(player);if(predicted==null)return false;
                if(action!=null&&!predicted.source.equals(landing.source)){outcome="PLACEMENT_TARGET_CHANGED";return true;}
                landing=predicted;offhand=player.getOffhandItem().is(Items.WATER_BUCKET);
                if(!offhand&&!player.getMainHandItem().is(Items.WATER_BUCKET)){int slot=slot(player,Items.WATER_BUCKET);if(slot<0){outcome="WATER_BUCKET_UNAVAILABLE";return true;}actor.select(token,slot);if(!player.getMainHandItem().is(Items.WATER_BUCKET))return false;}
                actor.aimImmediately(token,landing.aim);
                if(player.getEyePosition().distanceTo(landing.aim)<=player.blockInteractionRange()-.05&&now>lastUse+1&&tries<3&&(offhand||player.getMainHandItem().is(Items.WATER_BUCKET))){
                    // Check the exact native ray immediately before use; never create water by writing blocks.
                    var hit=player.level().clip(new ClipContext(player.getEyePosition(),player.getEyePosition().add(player.getLookAngle().scale(player.blockInteractionRange())),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player));
                    if(hit.getType()==HitResult.Type.BLOCK&&hit.getDirection()==Direction.UP&&hit.getBlockPos().equals(landing.support)){action=UUID.randomUUID();lastUse=now;tries++;actor.useOnce(token,action,offhand?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND);}
                }
                return false;
            }
            var state=player.level().getBlockState(landing.source);
            if(!state.is(Blocks.WATER)||!state.getFluidState().isSource()){
                if(outcome.equals("RECOVERY_SENT")&&(player.getMainHandItem().is(Items.WATER_BUCKET)||player.getOffhandItem().is(Items.WATER_BUCKET))){outcome="RECOVERED";return true;}
                outcome="SOURCE_CHANGED_NO_RECOVERY";return true;
            }
            // Let native water contact reset the fall. Do not remove the source while still descending.
            if(now-placedAt<3||player.fallDistance>0||player.getDeltaMovement().y<-.06||!(player.onGround()||player.isInWater()))return false;
            if(player.level().getBlockState(landing.support).getCollisionShape(player.level(),landing.support).isEmpty()){outcome="SUPPORT_CHANGED_NO_RECOVERY";return true;}
            if(settledAt<0)settledAt=now;if(now-settledAt<3)return false;
            if(player.getEyePosition().distanceTo(Vec3.atCenterOf(landing.source))>player.blockInteractionRange()-.1){outcome="SOURCE_OUT_OF_REACH";return true;}
            offhand=player.getOffhandItem().is(Items.BUCKET);
            if(!offhand&&!player.getMainHandItem().is(Items.BUCKET)){int slot=slot(player,Items.BUCKET);if(slot<0){outcome="EMPTY_BUCKET_UNAVAILABLE";return true;}actor.select(token,slot);return false;}
            actor.aimImmediately(token,Vec3.atCenterOf(landing.source));
            var hit=player.level().clip(new ClipContext(player.getEyePosition(),player.getEyePosition().add(player.getLookAngle().scale(player.blockInteractionRange())),ClipContext.Block.OUTLINE,ClipContext.Fluid.SOURCE_ONLY,player));
            if(hit.getType()==HitResult.Type.BLOCK&&hit.getBlockPos().equals(landing.source)&&now>lastUse+2){action=UUID.randomUUID();lastUse=now;outcome="RECOVERY_SENT";actor.useOnce(token,action,offhand?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND);}
            return false;
        }
        void record(){var data=new LinkedHashMap<String,Object>();data.put("state",outcome);data.put("operation",token.toString());data.put("source",List.of(landing.source.getX(),landing.source.getY(),landing.source.getZ()));data.put("predictedFallDistance",landing.drop);data.put("placed",placed);data.put("healthBefore",healthBefore);data.put("healthAfter",player.getHealth());data.put("placementAttempts",tries);data.put("tick",player.level().getServer().getTickCount());RESULTS.computeIfAbsent(player.level().getServer(),s->new HashMap<>()).put(player.getUUID(),Map.copyOf(data));}
        void finish(){try{if(actor!=null){actor.stop(token);actor.controls().release(token);}}finally{record();}}
    }
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        var server=event.getServer();if(!dev.mineagent.runtime.neoforge.WorldIdentityRuntime.ready(server))return;var active=ACTIVE.computeIfAbsent(server,s->new HashMap<>());var retry=RETRY.computeIfAbsent(server,s->new HashMap<>());
        for(var p:server.getPlayerList().getPlayers())if(!active.containsKey(p.getUUID())&&server.getTickCount()>=retry.getOrDefault(p.getUUID(),0)&&allowed(p)&&hasWater(p)){var landing=predict(p);if(landing!=null)active.put(p.getUUID(),new Clutch(p,landing));}
        for(var entry:List.copyOf(active.entrySet())){var clutch=entry.getValue();boolean done;try{done=clutch.tick();}catch(Exception failure){clutch.outcome="NATIVE_USE_REJECTED";done=true;}if(done){if(!clutch.completing&&clutch.current()&&clutch.actor.controls().owns(clutch.token,BodyDomain.INVENTORY)&&clutch.player.getInventory().getSelectedSlot()!=clutch.originalSlot){clutch.completing=true;clutch.restoreAt=server.getTickCount();clutch.actor.select(clutch.token,clutch.originalSlot);continue;}clutch.finish();active.remove(entry.getKey());retry.put(entry.getKey(),server.getTickCount()+10);}}
    }
    @SubscribeEvent public static void stopped(net.neoforged.neoforge.event.server.ServerStoppingEvent event){var active=ACTIVE.remove(event.getServer());if(active!=null)for(var clutch:active.values())clutch.finish();RESULTS.remove(event.getServer());RETRY.remove(event.getServer());}
}
