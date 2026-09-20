package dev.mineagent.runtime.neoforge.client.webui;
import dev.mineagent.runtime.neoforge.content.*;
import dev.mineagent.runtime.neoforge.client.objects.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class RuntimeObjectSmokeClient {
    private static boolean opened,backup,capturing,done,ysmExpected;private static volatile boolean imageDone;private static int ticks,phase,after;private static List<String> frozen;
    private static List<RuntimeObjectFreezeProof.Pose> frozenPoses;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!RuntimeObjectSmokeServer.enabled()||done)return;var mc=Minecraft.getInstance();ticks++;var host=WebGuiHostAdapter.INSTANCE;Path root=mc.gameDirectory.toPath().resolve("runtime-object-evidence");Files.createDirectories(root);
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen s){backup=true;var f=s.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(s)).proceed(false,false);}
        if(mc.player!=null&&!opened){opened=true;host.open();}
        if(RuntimeObjectSmokeServer.ysmAgent!=null&&!ysmExpected){ysmExpected=true;dev.mineagent.runtime.neoforge.client.ysm.YsmRenderObserver.expect(RuntimeObjectSmokeServer.ysmAgent);}
        if(RuntimeObjectSmokeServer.failure!=null||ticks>5500){Files.writeString(root.resolve("client-failure.json"),new com.google.gson.Gson().toJson(Map.of("phase",phase,"error",RuntimeObjectSmokeServer.failure==null?"TIMEOUT":RuntimeObjectSmokeServer.failure,"drawn",RuntimeObjectRenderer.drawn)));done=true;host.close();mc.stop();return;}
        if(phase==6&&ticks>=after){done=true;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_RUNTIME_OBJECT_OK");mc.stop();return;}
        if(mc.level==null)return;
        if(phase==0&&RuntimeObjectSmokeServer.ready&&host.ready()&&RuntimeObjectSmokeServer.objects.stream().allMatch(id->RuntimeObjectRenderer.drawn.getOrDefault(id,0)>10)){
            if(!capturing){if(RuntimeObjectAssets.observedChunks.values().stream().noneMatch(n->n>1))throw new IllegalStateException("OBJECT_MULTICHUNK_NOT_EXERCISED");Files.writeString(root.resolve("rendered.json"),new com.google.gson.Gson().toJson(Map.of("draws",RuntimeObjectRenderer.drawn,"objects",snapshot(),"browserReady",host.ready(),"assetChunks",RuntimeObjectAssets.observedChunks)));capture(root.resolve("with-webgui.png"));}
            if(imageDone){host.close();phase=1;after=ticks+20;capturing=false;imageDone=false;}
        }
        if(phase==1&&ticks>=after){var entity=mc.level.getEntity(RuntimeObjectSmokeServer.objects.getFirst());if(!(entity instanceof RuntimeObjectEntity object))throw new IllegalStateException("OBJECT_CLIENT_ENTITY_MISSING");mc.gameMode.interact(mc.player,object,new EntityHitResult(object,object.position().add(0,1,0)),InteractionHand.MAIN_HAND);phase=2;}
        if(phase==2&&RuntimeObjectSmokeServer.interactionDone){RuntimeObjectSmokeServer.closedGui=true;phase=3;}
        if(phase==3&&RuntimeObjectSmokeServer.verified){
            if(ysmExpected){var r=dev.mineagent.runtime.neoforge.client.ysm.YsmRenderObserver.snapshot();if(r.ysmCalls()<10)return;Files.writeString(root.resolve("ysm-render.json"),new com.google.gson.Gson().toJson(Map.of("ysm",r.ysmCalls(),"vanilla",r.vanillaCalls(),"duplicates",r.duplicateDraws())));if(r.duplicateDraws()!=0)throw new IllegalStateException("OBJECT_JOINT_DUPLICATE_RENDER");}
            if(!capturing)capture(root.resolve("world-only.png"));if(imageDone){RuntimeObjectSmokeServer.finish=true;phase=4;capturing=false;imageDone=false;}
        }
        if(phase==4&&RuntimeObjectSmokeServer.disabled){phase=45;after=ticks+10;}
        if(phase==45&&ticks>=after){frozen=snapshot();frozenPoses=poses();Files.writeString(root.resolve("freeze-client-before.json"),new com.google.gson.Gson().toJson(frozenPoses));phase=5;after=ticks+60;}
        if(phase==5&&ticks>=after&&RuntimeObjectSmokeServer.frozenServerVerified){
            var current=poses();Files.writeString(root.resolve("freeze-client-after.json"),new com.google.gson.Gson().toJson(current));
            if(!RuntimeObjectFreezeProof.aligned(frozenPoses,RuntimeObjectSmokeServer.frozenServer)||!RuntimeObjectFreezeProof.aligned(current,RuntimeObjectSmokeServer.frozenServer)||RuntimeObjectSmokeServer.objects.stream().anyMatch(id->!"READY".equals(RuntimeObjectAssets.diagnostic(((RuntimeObjectEntity)mc.level.getEntity(id)).header().asset()))))throw new IllegalStateException("DISABLED_OBJECT_CLIENT_OUT_OF_SYNC");
            Files.writeString(root.resolve("client-result.json"),new com.google.gson.Gson().toJson(Map.of("status","NATIVE_OBJECT_MESH_PHYSICS_VERIFIED","frozenObjects",frozen,"draws",RuntimeObjectRenderer.drawn,"providerCalls",0,"origin","EXPLICIT_IMPORT","serverPoseUnchanged",true,"clientPoseWithinNativeCodecTolerance",true,"positionQuantum",RuntimeObjectFreezeProof.POSITION_QUANTUM)));
            phase=6;host.close();mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Runtime object fixture complete"));after=ticks+20;
        }
    }
    private static List<String> snapshot(){var level=Minecraft.getInstance().level;return RuntimeObjectSmokeServer.objects.stream().map(id->{var e=(RuntimeObjectEntity)level.getEntity(id);return id+"|"+e.position()+"|yaw="+e.getYRot()+"|"+RuntimeObjectAssets.diagnostic(e.header().asset());}).toList();}
    private static List<RuntimeObjectFreezeProof.Pose> poses(){var level=Minecraft.getInstance().level;return RuntimeObjectSmokeServer.objects.stream().map(id->RuntimeObjectFreezeProof.sample((RuntimeObjectEntity)level.getEntity(id))).toList();}
    private static void capture(Path path){capturing=true;net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget(),image->{try(image){image.writeToFile(path);}catch(Exception e){throw new IllegalStateException(e);}imageDone=true;});}
}
