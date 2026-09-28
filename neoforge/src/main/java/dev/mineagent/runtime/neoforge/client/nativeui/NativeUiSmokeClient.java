package dev.mineagent.runtime.neoforge.client.nativeui;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.ui.ConversationAgentTools;
import net.minecraft.client.Minecraft;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Isolated production-JAR fixture. No model calls; drives the real server RPC and KubeJS/LDLib2 runtime. */
public final class NativeUiSmokeClient {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String SOURCE="""
        {"id":"quest","title":"DivZero native UI","surface":"HUD","data":{"query":"","balance":25,"done":false},
        "root":{"id":"root","type":"column","style":"width: 270; height: 180; padding-all: 10; background: #d9222939; gap: 5;","children":[
        {"id":"heading","type":"label","text":"DivZero / LDLib2 + KubeJS","style":"height: 18; color: #ffffff;"},
        {"id":"search","type":"input","bind":"query","style":"height: 20;"},
        {"id":"balance","type":"label","bind":"balance","style":"height: 18; color: #ffe16a;"},
        {"id":"progress","type":"progress","value":0.6,"style":"height: 12;"},
        {"id":"buy","type":"button","text":"Update balance / toggle task","style":"height: 22;","events":{"click":[{"op":"set","key":"balance","value":17},{"op":"toggle","key":"done"}]}},
        {"id":"task","type":"toggle","text":"Task complete","bind":"done"}]}}
        """;
    private static int ticks,stage,wait,setupTicks;private static boolean busy,finished,setupRequested,setupAccepted;private static UUID agent;private static UIElement oldWidget;private static final List<Object> receipts=new ArrayList<>();
    public static void tick(){
        if(!Boolean.getBoolean("mineagent.nativeUiSmoke")||finished)return;var mc=Minecraft.getInstance();
        if(++ticks>6000){fail(new IllegalStateException("NATIVE_UI_SMOKE_TIMEOUT stage="+stage));return;}
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null||busy)return;
        try{
            if(!setupRequested){setupRequested=true;net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new dev.mineagent.runtime.neoforge.network.MineAgentPayloads.PanelRequest());return;}
            if(!setupAccepted){var values=dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot().values();String fingerprint=values.getOrDefault("security.identityFingerprint","");if(fingerprint.isEmpty()||!dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.signatureValid())return;new dev.mineagent.runtime.client.trust.ServerTrustStore(mc.gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties")).confirm("local-integrated",fingerprint,Base64.getDecoder().decode(values.get("security.identityPublicKey")));mc.player.connection.sendCommand("ai accept");setupAccepted=true;return;}
            if(++setupTicks<35)return;
            if(agent==null){busy=true;var owner=mc.player.getUUID();var server=mc.getSingleplayerServer();server.submit(()->{var p=server.getPlayerList().getPlayer(owner);server.getPlayerList().op(p.nameAndId());p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);return MineAgentRuntimeServices.bodies(server).createPersistentAt("NativeUiBuilder",owner,p.level(),p.position().add(2,0,0)).agentId();}).whenComplete((id,error)->mc.execute(()->{busy=false;if(error!=null){fail(error);return;}agent=id;mc.setScreen(null);}));return;}
            if(stage==0){call("set_native_ui",base(0).put("source",SOURCE),r->{applied(r,1);require(mc.screen==null,"PASSIVE_HUD_OPENED_SCREEN");stage=1;});return;}
            if(stage==1){if(++wait<30)return;screenshot("01-passive.png");wait=0;call("control_native_ui",base(1).put("action","interact"),r->{applied(r,2);stage=2;});return;}
            if(stage==2){if(++wait<15)return;wait=0;((TextField)NativeInterfacesClient.smokeWidget("quest","search")).setText("oak");var event=UIEvent.create(UIEvents.MOUSE_DOWN);event.target=NativeInterfacesClient.smokeWidget("quest","buy");event.button=0;UIEventDispatcher.dispatchEvent(event);oldWidget=NativeInterfacesClient.smokeWidget("quest","progress");call("inspect_native_ui",JSON.createObjectNode().put("id","quest"),r->{var client=JSON.valueToTree(r).path("client").path("views").get(0);require(client.path("data").path("query").asText().equals("oak")&&client.path("data").path("balance").asInt()==17&&client.path("data").path("done").asBoolean(),"KUBEJS_INPUT_OR_BUTTON");stage=3;});return;}
            if(stage==3){var args=base(2);args.putObject("data").put("balance",19);call("patch_native_ui_data",args,r->{applied(r,3);require(oldWidget==NativeInterfacesClient.smokeWidget("quest","progress"),"DATA_REBUILT_TREE");stage=4;});return;}
            if(stage==4){screenshot("02-interactive.png");call("set_native_ui",base(3).put("source",SOURCE.replace("#d9222939","#e0101420").replace("DivZero / LDLib2 + KubeJS","Live revised / input retained")),r->{applied(r,4);require(((TextField)NativeInterfacesClient.smokeWidget("quest","search")).getValue().equals("oak"),"HOT_UPDATE_LOST_DRAFT");oldWidget=NativeInterfacesClient.smokeWidget("quest","search");stage=5;});return;}
            if(stage==5){call("set_native_ui",base(4).put("source",SOURCE.replace("width: 270","unknown-property: 270")),r->{require("REJECTED".equals(r.get("status")),"BAD_STYLE_ACCEPTED");require(oldWidget==NativeInterfacesClient.smokeWidget("quest","search"),"FAILED_BUILD_REPLACED_OLD_TREE");stage=6;});return;}
            if(stage==6){screenshot("03-revised.png");call("set_native_ui",base(1).put("source",SOURCE),r->{require("REJECTED".equals(r.get("status")),"STALE_UPDATE_ACCEPTED");stage=7;});return;}
            if(stage==7){call("control_native_ui",base(4).put("action","release"),r->{applied(r,5);require(mc.screen==null,"HUD_RELEASE_LEFT_SCREEN");stage=8;});return;}
            if(stage==8){if(++wait<25)return;wait=0;screenshot("04-restored-hud.png");call("inspect_native_ui",JSON.createObjectNode().put("id","quest"),r->{var n=JSON.valueToTree(r);require(n.path("saved").path("revision").asLong()==5,"STORE_REVISION");require(!n.path("client").path("views").get(0).path("interactive").asBoolean(),"HUD_STILL_INTERACTIVE");stage=9;});return;}
            if(stage==9){if(++wait<20)return;wait=0;NativeWorkspaceScreen.open();stage=10;return;}
            if(stage==10){if(++wait<35)return;wait=0;require(mc.screen instanceof NativeWorkspaceScreen,"NATIVE_WORKSPACE_NOT_OPEN");screenshot("05-native-workspace.png");AgentProfileScreen.open(agent,"NativeUiBuilder");stage=11;return;}
            if(stage==11){if(++wait<35)return;wait=0;require(mc.screen instanceof AgentProfileScreen,"AI_PROFILE_NOT_OPEN");screenshot("06-agent-profile.png");stage=12;return;}
            if(stage==12){if(++wait<20)return;Files.writeString(output().resolve("result.json"),JSON.writeValueAsString(Map.of("status","PASS","modelCalls",0,"receipts",receipts,"coverage",List.of("server_rpc","persistent_revision","kubejs_tree","native_hud","explicit_interaction","live_input","button_binding","incremental_data","hot_structure","retained_draft","bad_candidate_rollback","stale_rejection"))));finished=true;mc.stop();}
        }catch(Exception error){fail(error);}
    }
    private static ObjectNode base(long revision){return JSON.createObjectNode().put("id","quest").put("expected_revision",revision);}
    private static void applied(Map<String,Object> result,long revision){require("APPLIED".equals(result.get("status"))&&((Number)result.get("revision")).longValue()==revision,"APPLY_RECEIPT "+result);}
    private static void require(boolean yes,String message){if(!yes)throw new IllegalStateException(message);}
    private static void call(String tool,JsonNode arguments,java.util.function.Consumer<Map<String,Object>> accept){
        busy=true;var mc=Minecraft.getInstance();var owner=mc.player.getUUID();var server=mc.getSingleplayerServer();
        server.submit(()->ConversationAgentTools.execute(server.getPlayerList().getPlayer(owner),agent,UUID.randomUUID(),tool,arguments.toString(),()->true)).thenCompose(f->f).whenComplete((result,error)->mc.execute(()->{
            busy=false;try{if(error!=null)throw new IllegalStateException(error);receipts.add(Map.of("tool",tool,"result",result));accept.accept(result);}catch(Exception failure){fail(failure);}
        }));
    }
    private static Path output()throws Exception{return Files.createDirectories(Minecraft.getInstance().gameDirectory.toPath().resolve("native-ui-smoke"));}
    private static void screenshot(String name)throws Exception{Path target=output().resolve(name);net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget(),image->{try(image){image.writeToFile(target);}catch(Exception e){fail(e);}});}
    private static void fail(Throwable error){if(finished)return;finished=true;try{Files.writeString(output().resolve("failure.json"),JSON.writeValueAsString(Map.of("stage",stage,"error",error.toString(),"receipts",receipts)));}catch(Exception ignored){}dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.error("NATIVE_UI_SMOKE_FAILED stage={}",stage,error);Minecraft.getInstance().stop();}
    private NativeUiSmokeClient(){}
}
