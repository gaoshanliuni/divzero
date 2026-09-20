package dev.mineagent.runtime.neoforge.client;

import com.google.gson.Gson;
import dev.mineagent.runtime.neoforge.body.BodyRecoverySmokeServer;
import dev.mineagent.runtime.neoforge.client.ysm.YsmRenderObserver;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;

/** Native read/render observations only. No OS mouse/keyboard and no browser control. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class BodyRecoverySmokeClient {
    private static final Gson JSON=new Gson();
    private static final Map<String,Object> observations=new LinkedHashMap<>();
    private static final Map<String,net.minecraft.world.entity.player.Player> displayed=new HashMap<>();
    private static String watching="";
    private static int ticks;
    private static boolean busy,finished;
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("body-recovery-evidence").resolve(BodyRecoverySmokeServer.stage()).resolve("client");}
    private static List<String> required(){return switch(BodyRecoverySmokeServer.stage()){
        case "prepare-death"->List.of("prepared");
        case "resume-death"->List.of("restored-death","same-jvm-respawn","before-credits");
        case "resume-end"->List.of("restored-end","live-end-return","hardcore-spectator");
        case "resume-hardcore"->List.of("hardcore-restored","explicit-survival");
        case "verify-mode"->List.of("final-visible","removed");
        default->throw new IllegalStateException("BODY_RECOVERY_CLIENT_STAGE");
    };}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.bodyRecoverySmoke")||finished)return;var mc=Minecraft.getInstance();ticks++;Files.createDirectories(root());
        if(!BodyRecoverySmokeServer.failure.isEmpty()||ticks>4000){finished=true;Files.writeString(root().resolve("failure.json"),JSON.toJson(Map.of("ticks",ticks,"error",BodyRecoverySmokeServer.failure,"wanted",BodyRecoverySmokeServer.wanted)));mc.stop();throw new IllegalStateException("BODY_RECOVERY_NATIVE_FAILED");}
        if(BodyRecoverySmokeServer.done&&!busy){
            for(String name:required())if(!observations.containsKey(name))throw new IllegalStateException("BODY_RECOVERY_OBSERVATION_MISSING_"+name);
            Files.writeString(root().resolve("result.json"),JSON.toJson(Map.of("stage",BodyRecoverySmokeServer.stage(),"observations",observations,"modelCalls",0,"systemInputInjected",false,"fullV1",false)));
            finished=true;mc.stop();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_BODY_RECOVERY_{}_OK",BodyRecoverySmokeServer.stage().toUpperCase(Locale.ROOT).replace('-','_'));return;
        }
        if(mc.screen instanceof dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen)mc.setScreen(null);
        if(mc.level==null||mc.player==null||BodyRecoverySmokeServer.agent==null||busy)return;
        var id=BodyRecoverySmokeServer.agent;var label=BodyRecoverySmokeServer.wanted;
        if(!watching.equals(label)){watching=label;YsmRenderObserver.expect(id);}
        var remote=mc.level.getEntity(id);
        if(ticks%40==0)Files.writeString(root().resolve("diagnostic.json"),JSON.toJson(Map.of("ticks",ticks,"label",label,"viewerDimension",mc.level.dimension().identifier().toString(),"viewerPosition",mc.player.position().toString(),"remote",remote==null?"missing":remote.position().toString(),"draws",YsmRenderObserver.snapshot().vanillaCalls(),"screen",mc.screen==null?"none":mc.screen.getClass().getName())));
        if(label.isBlank()||BodyRecoverySmokeServer.seen.contains(label))return;
        if(label.equals("removed")){
            if(remote!=null)return;var value=Map.of("removed",true,"uuid",id,"systemInputInjected",false);observations.put(label,value);Files.writeString(root().resolve(label+".json"),JSON.toJson(value));BodyRecoverySmokeServer.seen.add(label);return;
        }
        if(!(remote instanceof net.minecraft.world.entity.player.Player p)||p.getHealth()<=0||YsmRenderObserver.snapshot().vanillaCalls()<8)return;
        if(p.getHealth()!=BodyRecoverySmokeServer.wantedHealth||!mc.level.dimension().identifier().toString().equals(BodyRecoverySmokeServer.wantedDimension))return;
        if(label.equals("same-jvm-respawn")&&p==displayed.get("restored-death")||label.equals("live-end-return")&&p==displayed.get("restored-end"))return;
        boolean spectator=label.startsWith("hardcore-");if(p.isSpectator()!=spectator)return;
        var value=new LinkedHashMap<String,Object>();value.put("uuid",id);value.put("javaBody",System.identityHashCode(p));value.put("class",p.getClass().getName());value.put("health",p.getHealth());value.put("spectator",p.isSpectator());value.put("dimension",mc.level.dimension().identifier().toString());value.put("position",p.position().toString());value.put("draws",YsmRenderObserver.snapshot().vanillaCalls());value.put("systemInputInjected",false);
        observations.put(label,value);displayed.put(label,p);Files.writeString(root().resolve(label+".json"),JSON.toJson(value));busy=true;
        net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve(label+".png"));mc.execute(()->{BodyRecoverySmokeServer.seen.add(label);busy=false;});}catch(Exception e){mc.execute(()->{finished=true;mc.stop();throw new IllegalStateException(e);});}});
    }
}
