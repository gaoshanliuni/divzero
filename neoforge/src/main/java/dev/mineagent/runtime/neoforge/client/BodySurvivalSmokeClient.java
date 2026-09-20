package dev.mineagent.runtime.neoforge.client;
import com.google.gson.Gson;
import dev.mineagent.runtime.neoforge.body.BodySurvivalSmokeServer;
import dev.mineagent.runtime.neoforge.client.ysm.YsmRenderObserver;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;

/** Reads real remote-player tracking/rendering and saves Native screenshots; no OS input or model. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class BodySurvivalSmokeClient {
    private static final Gson JSON=new Gson();private static String watching="";private static boolean busy,finished,humanSent;private static int ticks;
    private static final Map<String,Object> observations=new LinkedHashMap<>();
    private static net.minecraft.world.entity.player.Player liveBody;
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("body-survival-evidence/client");}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.bodySurvivalSmoke")||finished)return;var mc=Minecraft.getInstance();ticks++;Files.createDirectories(root());
        if(!BodySurvivalSmokeServer.failure.isEmpty()||ticks>3600){finished=true;Files.writeString(root().resolve("failure.json"),JSON.toJson(Map.of("ticks",ticks,"error",BodySurvivalSmokeServer.failure,"wanted",BodySurvivalSmokeServer.wanted)));mc.stop();throw new IllegalStateException("BODY_SURVIVAL_NATIVE_FAILED");}
        if(mc.screen instanceof dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen)mc.setScreen(null);
        if(BodySurvivalSmokeServer.humanRespawn&&mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen&&!humanSent){humanSent=true;mc.player.connection.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));}
        if(mc.level==null||mc.player==null||BodySurvivalSmokeServer.agent==null||busy)return;
        String wanted=BodySurvivalSmokeServer.wanted;
        if(!wanted.equals(watching)){watching=wanted;YsmRenderObserver.expect(BodySurvivalSmokeServer.agent);}
        if(ticks%40==0){
            var remote=mc.level.getEntity(BodySurvivalSmokeServer.agent);var diagnostic=new LinkedHashMap<String,Object>();
            diagnostic.put("ticks",ticks);diagnostic.put("wanted",wanted);diagnostic.put("screen",mc.screen==null?"none":mc.screen.getClass().getName());
            diagnostic.put("viewer",List.of(mc.player.getX(),mc.player.getY(),mc.player.getZ(),mc.player.getYRot(),mc.player.getXRot()));
            diagnostic.put("flying",mc.player.getAbilities().flying);diagnostic.put("remote",remote==null?"missing":List.of(remote.getId(),remote.getX(),remote.getY(),remote.getZ()));
            diagnostic.put("frames",YsmRenderObserver.snapshot().uniqueFrames());diagnostic.put("vanillaDraws",YsmRenderObserver.snapshot().vanillaCalls());
            Files.writeString(root().resolve("diagnostic.json"),JSON.toJson(diagnostic));
        }
        if(!wanted.isEmpty()&&!BodySurvivalSmokeServer.seen.contains(wanted)){
            var entity=mc.level.getEntity(BodySurvivalSmokeServer.agent);
            if(entity instanceof net.minecraft.world.entity.player.Player player&&YsmRenderObserver.snapshot().vanillaCalls()>=8){
                if(wanted.startsWith("dead")&&player.getHealth()>0||!wanted.startsWith("dead")&&player.getHealth()<=0)return;
                if(wanted.startsWith("respawn")&&player==liveBody)return;
                if(wanted.startsWith("dead")&&player!=liveBody)return;
                if(!wanted.startsWith("dead"))liveBody=player;
                var data=new LinkedHashMap<String,Object>();data.put("stage",wanted);data.put("uuid",player.getUUID());data.put("entityId",player.getId());data.put("clientJavaBody",System.identityHashCode(player));data.put("clientClass",player.getClass().getName());data.put("health",player.getHealth());data.put("position",List.of(player.getX(),player.getY(),player.getZ()));data.put("vanillaDraws",YsmRenderObserver.snapshot().vanillaCalls());data.put("systemInputInjected",false);
                observations.put(wanted,data);Files.writeString(root().resolve(wanted+".json"),JSON.toJson(data));busy=true;String stage=wanted;
                net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve(stage+".png"));mc.execute(()->{BodySurvivalSmokeServer.seen.add(stage);busy=false;});}catch(Exception e){mc.execute(()->{finished=true;mc.stop();throw new IllegalStateException(e);});}});
            }
        }
        if(BodySurvivalSmokeServer.done&&!busy){for(String required:List.of("spawn","dead-drop","respawn-drop","dead-keep","respawn-keep"))if(!observations.containsKey(required))throw new IllegalStateException("MISSING_BODY_RENDER_"+required);Files.writeString(root().resolve("result.json"),JSON.toJson(Map.of("status","NATIVE_REMOTE_BODY_LIFECYCLE_RENDERED","observations",observations,"humanRespawnPacket",humanSent,"modelCalls",0,"systemInputInjected",false,"fullV1",false)));finished=true;mc.stop();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_BODY_SURVIVAL_OK native=true providerCalls=0");}
    }
}
