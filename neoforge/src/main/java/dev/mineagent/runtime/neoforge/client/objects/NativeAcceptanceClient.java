package dev.mineagent.runtime.neoforge.client.objects;

import dev.mineagent.runtime.neoforge.ui.NativeAcceptanceSmoke;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;

/** Opt-in native observation. No system mouse/keyboard injection or synthetic render results. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class NativeAcceptanceClient {
    private static int ticks,rigTicks,maxCrowd;
    private static boolean stopped,crowdPicture,terrainPicture;
    private static volatile int screenshotsPending;
    private static final List<String> failures=new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final Map<String,Object> evidence=new LinkedHashMap<>();
    private static final Map<UUID,int[]> poses=new LinkedHashMap<>();
    private static Path root;
    private static void check(boolean value,String failure){if(!value)failures.add(failure);}
    private static void screenshot(Minecraft mc,String name){
        screenshotsPending++;
        Screenshot.takeScreenshot(mc.getMainRenderTarget(),img->{try(img){img.writeToFile(root.resolve(name+".png"));}catch(Exception e){failures.add("SCREENSHOT_"+e.getClass().getSimpleName());}finally{screenshotsPending--;}});
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!NativeAcceptanceSmoke.enabled()||stopped)return;
        var mc=Minecraft.getInstance();
        try{
            if(root==null)root=Files.createDirectories(mc.gameDirectory.toPath().resolve("native-acceptance"));
            if(++ticks>40000)throw new IllegalStateException("CLIENT_ACCEPTANCE_TIMEOUT");
            if(mc.player==null||mc.level==null)return;
            mc.options.pauseOnLostFocus=false;
            if(mc.screen!=null)mc.setScreen(null);
            mc.options.hideGui=true;
            String phase=NativeAcceptanceSmoke.phase;
            if(phase.equals("RIG_VIEW")&&!NativeAcceptanceSmoke.rigVisualDone){
                rigTicks++;
                if(rigTicks==20)CreatureRigTelemetry.clear();
                if(rigTicks==60||rigTicks==74||rigTicks==89)screenshot(mc,"rig-frame-"+rigTicks);
                if(rigTicks>=200&&screenshotsPending==0){
                    UUID fixture=NativeAcceptanceSmoke.fixtureRig,real=NativeAcceptanceSmoke.realRig;
                    check(fixture!=null&&RuntimeCreatureRenderer.drawn.getOrDefault(fixture,0)>10,"FIXTURE_NOT_RENDERED");
                    if(fixture!=null){
                        evidence.put("deterministicRig",CreatureRigTelemetry.snapshot(fixture));
                        check(CreatureRigTelemetry.variation(fixture,"body",false)<.001,"BODY_DID_NOT_STAY_FIXED");
                        check(CreatureRigTelemetry.variation(fixture,"arm",false)>.5,"ARM_NOT_ANIMATED");
                        check(CreatureRigTelemetry.variation(fixture,"head",false)>.3,"HEAD_NOT_ANIMATED");
                        check(CreatureRigTelemetry.variation(fixture,"forearm",true)>.2,"FOREARM_PARENT_NOT_INHERITED");
                        check(CreatureRigTelemetry.variation(fixture,"hand",true)>.2,"HAND_ANCESTOR_NOT_INHERITED");
                    }
                    check(real!=null&&RuntimeCreatureRenderer.drawn.getOrDefault(real,0)>10,"REAL_MODEL_NOT_RENDERED");
                    if(real!=null){
                        var traces=CreatureRigTelemetry.snapshot(real);evidence.put("deepseekRig",traces);
                        check(traces.keySet().stream().anyMatch(b->!b.equals("body")&&CreatureRigTelemetry.variation(real,b,false)>.05),"REAL_MODEL_NO_LOCAL_MOTION");
                    }
                    NativeAcceptanceSmoke.visualFailure=String.join(",",failures);
                    NativeAcceptanceSmoke.rigVisualDone=true;
                    save();
                }
            }
            if(phase.equals("TERRAIN")){
                for(var id:NativeAcceptanceSmoke.terrainIds){var body=mc.level.getPlayerByUUID(id);if(body!=null){var count=poses.computeIfAbsent(id,k->new int[2]);count[body.isCrouching()?0:1]++;}}
                if(!terrainPicture&&poses.values().stream().anyMatch(c->c[0]>2)){terrainPicture=true;screenshot(mc,"terrain-low-clearance");}
            }
            int visible=0;for(var id:NativeAcceptanceSmoke.crowdIds)if(mc.level.getPlayerByUUID(id)!=null)visible++;
            maxCrowd=Math.max(maxCrowd,visible);
            if(visible==64){NativeAcceptanceSmoke.crowdSeen=true;if(!crowdPicture){crowdPicture=true;screenshot(mc,"crowd-64");}}
            if(NativeAcceptanceSmoke.done&&screenshotsPending==0){check(maxCrowd==64,"CLIENT_CROWD_COUNT_"+maxCrowd);if(!NativeAcceptanceSmoke.crowdOnly())check(terrainPicture,"LOW_CLEARANCE_POSE_NOT_OBSERVED");save();stopped=true;dev.mineagent.runtime.neoforge.client.cinematic.CinematicCaptureClient.finish();}
        }catch(Exception e){failures.add(e.toString());try{save();}catch(Exception ignored){}NativeAcceptanceSmoke.visualFailure=String.join(",",failures);NativeAcceptanceSmoke.rigVisualDone=true;stopped=true;dev.mineagent.runtime.neoforge.client.cinematic.CinematicCaptureClient.finish();}
    }
    private static void save()throws Exception{
        evidence.put("status",failures.isEmpty()?"CLIENT_CHECKS_PASSED":"CLIENT_CHECKS_FAILED");evidence.put("phase",NativeAcceptanceSmoke.phase);evidence.put("failures",failures);evidence.put("maxSimultaneousClientAgents",maxCrowd);evidence.put("clientTerrainPoses",poses);evidence.put("osInputInjected",false);
        Files.writeString(root.resolve("client.json"),new com.google.gson.Gson().toJson(evidence));
    }
    private NativeAcceptanceClient(){}
}
