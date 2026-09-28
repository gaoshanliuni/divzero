package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;

/** Isolated UI acceptance. Does not invoke a provider or execute the edited script. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class NativeStudioSmokeClient {
    private static int ticks,stage,after;private static boolean busy,done;private static UUID agent;private static String draft;
    private static final List<Object> evidence=new ArrayList<>();
    private static Path root()throws Exception{return Files.createDirectories(Minecraft.getInstance().gameDirectory.toPath().resolve("native-studio-smoke"));}
    private static void require(boolean yes,String error){if(!yes)throw new IllegalStateException(error);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("mineagent.nativeStudioSmoke")||done)return;var mc=Minecraft.getInstance();
        try{
            if(++ticks>6000)throw new IllegalStateException("NATIVE_STUDIO_TIMEOUT_"+stage);if(mc.player==null||mc.getSingleplayerServer()==null||busy)return;
            if(ticks%40==0)Files.writeString(root().resolve("progress.json"),new Gson().toJson(Map.of("stage",stage,"ready",NativeWorkspaceConnection.ready(),"enabled",dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.enabled(),"screen",mc.screen==null?"NONE":mc.screen.getClass().getName(),"editor",NativeStudioPanel.smokeState())));
            if(stage==0){
                var snapshot=dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot();String fingerprint=snapshot.values().getOrDefault("security.identityFingerprint","");if(fingerprint.isBlank()||!dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.signatureValid())return;
                new dev.mineagent.runtime.client.trust.ServerTrustStore(mc.gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties")).confirm("local-integrated",fingerprint,Base64.getDecoder().decode(snapshot.values().get("security.identityPublicKey")));
                busy=true;var server=mc.getSingleplayerServer();UUID player=mc.player.getUUID();server.submit(()->{var p=server.getPlayerList().getPlayer(player);server.getPlayerList().op(p.nameAndId());dev.mineagent.runtime.neoforge.WorldActivationRuntime.decide(p.createCommandSourceStack(),true,null);return MineAgentRuntimeServices.bodies(server).createPersistentAt("StudioTest",player,p.level(),p.position().add(3,0,0)).agentId();}).whenComplete((id,error)->mc.execute(()->{busy=false;if(error!=null){fail(error);return;}agent=id;stage=1;}));return;
            }
            if(stage==1){if(!dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.enabled())return;NativeWorkspaceScreen.openForAgent(agent.toString(),"StudioTest");stage=2;return;}
            if(stage==2){if(!NativeWorkspaceConnection.ready())return;busy=true;WorkspacePanels.request("studio.write",Map.of("action","create","agentId",agent.toString(),"path","server/studio.js","source","// 中文注释\nvar greeting = \"你好\";\n1;","packageId","","packageRevision","0","targetSide","SERVER","confirmed","true")).whenComplete((receipt,error)->{busy=false;if(error!=null){fail(error);return;}draft=NativeStudioPanel.text(WorkspacePanels.state(receipt),"draftId");NativeStudioPanel.document((NativeWorkspaceScreen)mc.screen,draft);stage=3;});return;}
            if(stage==3){var editor=NativeStudioPanel.smokeEditor();if(editor==null)return;require(editor.find("你好",true),"NATIVE_SOURCE_FIND");require(editor.replace("你好","世界",true,true)==1,"NATIVE_SOURCE_REPLACE");editor.goTo(2);require(editor.getCursorLine()==1,"NATIVE_SOURCE_GOTO");NativeStudioPanel.smokeSave();stage=4;return;}
            if(stage==4){var state=NativeStudioPanel.smokeState();if(Boolean.TRUE.equals(state.get("busy"))||Boolean.TRUE.equals(state.get("dirty"))||((Number)state.getOrDefault("revision",0)).longValue()<2)return;require(NativeStudioPanel.smokeEditor().source().contains("世界"),"NATIVE_SAVE_READBACK");evidence.add(state);after=ticks+15;stage=5;return;}
            if(stage==5&&ticks>=after){capture("01-code-editor");NativeStudioPanel.smokeEditor().replace("世界","未保存",true,true);((NativeWorkspaceScreen)mc.screen).onClose();NativeWorkspaceScreen.openForAgent(agent.toString(),"StudioTest");NativeStudioPanel.document((NativeWorkspaceScreen)mc.screen,draft);stage=6;return;}
            if(stage==6){var state=NativeStudioPanel.smokeState();if(Boolean.TRUE.equals(state.get("busy")))return;require(NativeStudioPanel.smokeEditor().source().contains("未保存")&&Boolean.TRUE.equals(state.get("dirty")),"DRAFT_LOST_REOPENING_F2");evidence.add(Map.of("draftRestoredAfterF2",true));NativeLifecyclePanel.open((NativeWorkspaceScreen)mc.screen,null,NativeLifecyclePanel.Kind.RESOURCE);stage=7;after=ticks+25;return;}
            if(stage==7&&ticks>=after){var state=NativeLifecyclePanel.smokeState();require(Boolean.TRUE.equals(state.get("ready")),"RESOURCE_NATIVE_PAGE_NOT_READY");evidence.add(state);capture("02-local-resource-lifecycle");NativeLifecyclePanel.open((NativeWorkspaceScreen)mc.screen,null,NativeLifecyclePanel.Kind.CLIENT);stage=8;after=ticks+25;return;}
            if(stage==8&&ticks>=after){var state=NativeLifecyclePanel.smokeState();require(Boolean.TRUE.equals(state.get("ready")),"CLIENT_CODE_NATIVE_PAGE_NOT_READY");evidence.add(state);Files.writeString(root().resolve("result.json"),new Gson().toJson(Map.of("status","PASS","modelCalls",0,"scriptExecuted",false,"evidence",evidence)));done=true;mc.stop();}
        }catch(Exception error){fail(error);}
    }
    private static void capture(String name)throws Exception{var path=root().resolve(name+".png");net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget(),image->{try(image){image.writeToFile(path);}catch(Exception error){fail(error);}});}
    private static void fail(Throwable error){if(done)return;done=true;try{Files.writeString(root().resolve("failure.json"),new Gson().toJson(Map.of("stage",stage,"error",error.toString(),"evidence",evidence)));}catch(Exception ignored){}Minecraft.getInstance().stop();}
    private NativeStudioSmokeClient(){}
}
