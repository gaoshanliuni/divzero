package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import java.util.*;

/** Controlled native player input; body crouch/aim, nearest recipient and danger interruption are observed. */
@net.neoforged.fml.common.EventBusSubscriber(modid="mineagent_runtime")
public final class SocialGreetingSmokeServer {
    private static ServerPlayer near,far;private static UUID agent;private static int started;private static boolean dangerPhase;
    private static void guard(){if(!Boolean.getBoolean("mineagent.skillSmoke")||!"social_greeting".equals(System.getProperty("mineagent.skillSmokeMode")))throw new SecurityException("FIXTURE_DISABLED");}
    private static MineAgentPlayer body(ServerPlayer p){return MineAgentRuntimeServices.bodies(p.level().getServer()).body(agent).orElseThrow();}
    public static Map<String,Object> prepare(ServerPlayer p,UUID id){
        guard();agent=id;var server=p.level().getServer();var body=body(p);body.movementController().stop();body.snapTo(0.5,101,6.5,180,0);near=player(p,"GreetingNear",0.5,101,4.5);far=player(p,"GreetingFar",0.5,101,1.5);started=server.getTickCount();dangerPhase=false;
        return Map.of("near",near.getUUID(),"far",far.getUUID(),"agent",agent,"fixtureInput",true);
    }
    private static ServerPlayer player(ServerPlayer owner,String name,double x,double y,double z){var server=owner.level().getServer();var profile=new com.mojang.authlib.GameProfile(UUID.randomUUID(),name);var p=new ServerPlayer(server,owner.level(),profile,ClientInformation.createDefault());server.getPlayerList().placeNewPlayer(new MineAgentConnection(),p,net.minecraft.server.network.CommonListenerCookie.createInitial(profile,false));p.connection.markClientLoaded();p.teleportTo(owner.level(),x,y,z,Set.of(),0,0,true);return p;}
    private static void input(ServerPlayer player,boolean down){player.connection.handlePlayerInput(new net.minecraft.network.protocol.game.ServerboundPlayerInputPacket(new net.minecraft.world.entity.player.Input(false,false,false,false,false,down,false)));player.setShiftKeyDown(down);}
    @net.neoforged.bus.api.SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Pre event){
        if(!Boolean.getBoolean("mineagent.skillSmoke")||!"social_greeting".equals(System.getProperty("mineagent.skillSmokeMode"))||near==null||near.level().getServer()!=event.getServer())return;
        int age=event.getServer().getTickCount()-started;boolean down=age<30&&age%10<5;input(near,down);input(far,down);var b=body(near);near.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,b.getEyePosition());far.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,b.getEyePosition());
    }
    public static Map<String,Object> wave(ServerPlayer p){
        guard();int age=p.level().getServer().getTickCount()-started;var body=body(p);
        var observed=SocialGreetingRuntime.observation(body);if(Boolean.TRUE.equals(observed.get("active"))){if(!near.getUUID().equals(observed.get("player")))throw new IllegalStateException("GREETING_DID_NOT_PICK_NEAREST_PLAYER");double facing=body.getLookAngle().dot(near.getEyePosition().subtract(body.getEyePosition()).normalize());if(facing<.95)throw new IllegalStateException("GREETING_NOT_FACING_PLAYER");}
        if(Boolean.TRUE.equals(observed.get("completed"))){if(((Number)observed.get("crouches")).intValue()!=3||body.isShiftKeyDown())throw new IllegalStateException("GREETING_POSTURE_NOT_RESTORED");return Map.of("complete",true,"observation",observed,"nativeCrouchRestored",true);}
        return Map.of("complete",false,"observation",observed,"age",age,"diagnostic",Map.of("bodyPosition",body.position().toString(),"nearPosition",near.position().toString(),"nearShift",near.isShiftKeyDown(),"facing",near.getLookAngle().dot(body.getEyePosition().subtract(near.getEyePosition()).normalize()),"los",near.hasLineOfSight(body),"ground",body.onGround(),"canAct",body.canAct(),"sneak",body.requestedSneaking(),"busy",dev.mineagent.runtime.neoforge.skill.SkillRuntime.socialActionBusy(body)));
    }
    public static Map<String,Object> prepareDanger(ServerPlayer p){started=p.level().getServer().getTickCount();dangerPhase=true;return Map.of("start",started);}
    public static Map<String,Object> interruptWithDanger(ServerPlayer p){
        guard();int age=p.level().getServer().getTickCount()-started;var body=body(p);
        var observed=SocialGreetingRuntime.observation(body);
        if(dangerPhase&&Boolean.TRUE.equals(observed.get("active"))){var zombie=EntityType.ZOMBIE.create(p.level(),EntitySpawnReason.COMMAND);if(zombie==null)throw new IllegalStateException("ZOMBIE_CREATE");zombie.setNoAi(true);zombie.setPos(body.getX()+3,body.getY(),body.getZ());zombie.setTarget(body);if(!p.level().addFreshEntity(zombie))throw new IllegalStateException("ZOMBIE_SPAWN");dangerPhase=false;return Map.of("complete",false,"threat",zombie.getUUID());}
        if(!dangerPhase&&!Boolean.TRUE.equals(observed.get("active"))&&"DANGER_OR_NATIVE_ACTION".equals(observed.get("reason"))){if(body.isShiftKeyDown())throw new IllegalStateException("GREETING_CROUCH_RETAINED_IN_DANGER");return Map.of("complete",true,"interrupted",true,"observation",observed);}
        return Map.of("complete",false,"observation",observed,"age",age,"diagnostic",Map.of("bodyPosition",body.position().toString(),"nearPosition",near.position().toString(),"nearShift",near.isShiftKeyDown(),"facing",near.getLookAngle().dot(body.getEyePosition().subtract(near.getEyePosition()).normalize()),"los",near.hasLineOfSight(body),"ground",body.onGround(),"canAct",body.canAct(),"sneak",body.requestedSneaking(),"busy",dev.mineagent.runtime.neoforge.skill.SkillRuntime.socialActionBusy(body)));
    }
    private SocialGreetingSmokeServer(){}
}
