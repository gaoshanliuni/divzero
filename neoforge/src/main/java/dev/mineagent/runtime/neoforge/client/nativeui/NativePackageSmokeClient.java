package dev.mineagent.runtime.neoforge.client.nativeui;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.api.scoreboard.NumberFormatSpec;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.client.webui.*;
import dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime;
import dev.mineagent.runtime.worker.generation.*;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Signed transfer, authority, real score read/write and private native capture. No provider requests. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class NativePackageSmokeClient {
    private static final ObjectMapper JSON=new ObjectMapper();private static int stage,ticks,after;private static boolean busy,done;private static UUID pkg,target;private static String source,view;private static final List<Object> evidence=new ArrayList<>();
    private static Path root()throws Exception{return Files.createDirectories(Minecraft.getInstance().gameDirectory.toPath().resolve("native-package-smoke"));}
    private static void require(boolean value,String error){if(!value)throw new IllegalStateException(error);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("mineagent.nativePackageSmoke")||done)return;var mc=Minecraft.getInstance();
        try{
            if(++ticks>6000)throw new IllegalStateException("NATIVE_PACKAGE_TIMEOUT_"+stage);if(mc.player==null||mc.getSingleplayerServer()==null||busy)return;
            Files.writeString(root().resolve("steps.json"),JSON.writeValueAsString(Map.of("stage",stage,"evidence",evidence)));
            if(ticks%40==0)Files.writeString(root().resolve("progress.json"),JSON.writeValueAsString(Map.of("stage",stage,"ready",NativeWorkspaceConnection.ready(),"view",Objects.toString(view,""),"rendered",view!=null&&NativePackageViews.rendered(view))));
            if(stage==0){var snapshot=dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot();String fingerprint=snapshot.values().getOrDefault("security.identityFingerprint","");if(fingerprint.isBlank()||!dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.signatureValid())return;
                new dev.mineagent.runtime.client.trust.ServerTrustStore(mc.gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties")).confirm("local-integrated",fingerprint,Base64.getDecoder().decode(snapshot.values().get("security.identityPublicKey")));
                busy=true;var server=mc.getSingleplayerServer();UUID viewer=mc.player.getUUID();server.submit(()->{try{var p=server.getPlayerList().getPlayer(viewer);server.getPlayerList().op(p.nameAndId());dev.mineagent.runtime.neoforge.WorldActivationRuntime.decide(p.createCommandSourceStack(),true,null);
                    var grants=new HashSet<>(MineAgentRuntimeServices.permissions(server).trustedActions(viewer));grants.addAll(Set.of(PermissionAction.MANAGE_PACKAGES,PermissionAction.MANAGE_SCOREBOARD));MineAgentRuntimeServices.permissions(server).setTrustedActions(viewer,grants);
                    var port=new dev.mineagent.runtime.neoforge.scoreboard.NeoForgeScoreboardPort(server);require(port.createObjective("native_package","dummy","Native package","INTEGER",true,NumberFormatSpec.defaultFormat()).accepted(),"OBJECTIVE_SETUP");port.setScore("native_package","Alice",7);
                    source=MineAgentRuntimeServices.scoreboards(server).refreshSources().stream().filter(s->s.reference().equals("native_package")).findFirst().orElseThrow().sourceId().toString();return fixture(server,p);
                }catch(Exception e){throw new CompletionException(e);}}).whenComplete((id,error)->mc.execute(()->{busy=false;if(error!=null){fail(error);return;}pkg=id;stage=1;}));return;}
            if(stage==1){if(!dev.mineagent.runtime.neoforge.client.MineAgentClientTrustPrompt.enabled())return;NativeWorkspaceScreen.open();stage=2;return;}
            if(stage==2){if(!NativeWorkspaceConnection.ready())return;busy=true;NativeWorkspaceConnection.command("scoreview.bind",Map.of("packageId",pkg.toString(),"packageRevision","1","sourceId",source,"title","Native score"),UUID.randomUUID()).whenComplete((receipt,error)->mc.execute(()->{busy=false;try{if(error!=null)throw new CompletionException(error);require(receipt.code()==Code.APPLIED,"SCORE_BIND_FAILED:"+receipt);target=UUID.fromString(receipt.values().get("viewId"));stage=3;}catch(Exception failed){fail(failed);}}));return;}
            if(stage==3){busy=true;PackagePreviewClient.open(pkg,1,target).whenComplete((receipt,error)->mc.execute(()->{busy=false;if(error!=null){fail(error);return;}view=receipt.values().get("viewId");evidence.add(receipt);stage=4;}));return;}
            if(stage==4){if(!NativePackageViews.rendered(view)||!NativePackageViews.smokeData(view).containsKey("live"))return;var live=NativePackageViews.smokeData(view).get("live");if(!live.path("code").asText().equals("OBSERVED"))return;
                require(live.path("data").path("snapshot").toString().contains("Alice"),"REAL_SCORE_READ_MISSING");evidence.add(live);NativePackageViews.act(view,JSON.readTree("{\"action\":\"fill\",\"elementRef\":\"search\",\"value\":\"保留搜索\"}"),NativePackageViews.identity(view));NativePackageViews.act(view,JSON.readTree("{\"action\":\"click\",\"elementRef\":\"save\"}"),NativePackageViews.identity(view));stage=5;return;}
            if(stage==5){var saved=NativePackageViews.smokeData(view).get("saved");if(saved==null)return;require(saved.path("code").asText().equals("APPLIED"),"SCORE_WRITE_FAILED:"+saved);evidence.add(saved);busy=true;PackageContentClient.request(view,PackageContentClient.session(view),"scoreview.read",Map.of(),UUID.randomUUID()).whenComplete((receipt,error)->mc.execute(()->{busy=false;try{if(error!=null)throw new CompletionException(error);require(receipt.values().get("state").contains("Native applied"),"AUTHORITATIVE_READBACK");evidence.add(receipt);stage=6;}catch(Exception failed){fail(failed);}}));return;}
            if(stage==6){busy=true;NativePackageViews.capture(view).whenComplete((shot,error)->mc.execute(()->{busy=false;try{if(error!=null)throw new CompletionException(error);Files.write(root().resolve("private-package.png"),shot.png());require(shot.width()>100&&shot.height()>100,"NATIVE_CAPTURE_EMPTY");evidence.add(Map.of("privateCapture",true,"width",shot.width(),"height",shot.height(),"document",shot.documentId()));NativeWorkspaceScreen.toggle();require(!NativePackageViews.rendered(view),"HIDDEN_VIEW_STILL_RENDERED");stage=7;after=ticks+5;}catch(Exception failed){fail(failed);}}));return;}
            if(stage==7&&ticks>=after){NativeWorkspaceScreen.open();stage=8;return;}
            if(stage==8){if(!NativePackageViews.rendered(view))return;require(NativePackageViews.smokeData(view).get("query").asText().equals("保留搜索"),"NATIVE_PACKAGE_DRAFT_LOST");evidence.add(Map.of("draftPreserved",true,"recentPaintRequired",true));busy=true;PackagePreviewClient.openHud(pkg,1,target).whenComplete((receipt,error)->mc.execute(()->{busy=false;if(error!=null){fail(error);return;}view=receipt.values().get("viewId");stage=9;}));return;}
            if(stage==9){if(!NativePackageViews.rendered(view)||!NativePackageViews.smokeData(view).containsKey("live"))return;NativeWorkspaceScreen.toggle();stage=10;after=ticks+20;return;}
            if(stage==10&&ticks>=after){require(mc.screen==null&&NativePackageViews.rendered(view),"HUD_NOT_PASSIVE");evidence.add(Map.of("passiveHud",true));Files.writeString(root().resolve("result.json"),JSON.writeValueAsString(Map.of("status","PASS","modelCalls",0,"evidence",evidence)));done=true;mc.stop();}
        }catch(Exception error){fail(error);}
    }
    private static UUID fixture(net.minecraft.server.MinecraftServer server,net.minecraft.server.level.ServerPlayer viewer)throws Exception{
        var entries=new LinkedHashMap<String,RuntimeEntrypoint>();var files=new ArrayList<GeneratedFile>();
        for(String name:List.of("ui","hud")){String path="ui/"+name+".json";var source=JSON.readTree(SOURCE).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)source.path("view")).put("surface",name.equals("hud")?"HUD":"SCREEN");if(name.equals("hud")){((com.fasterxml.jackson.databind.node.ObjectNode)source.path("view").path("root")).put("style","width: 270; height: 170; padding-all: 12;");((com.fasterxml.jackson.databind.node.ObjectNode)source.path("view").path("root").path("children").get(2)).put("visible",false);}
            byte[] bytes=JSON.writeValueAsBytes(source);String hash=dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer.sha256(bytes);entries.put(name,new RuntimeEntrypoint(path,RuntimeResourceSide.CLIENT,hash));files.add(new GeneratedFile(path,RuntimeResourceSide.CLIENT,"application/json",hash,bytes));}
        var parsed=new ParsedRuntimePackage("Native signed score","1.0.0",RuntimePackageType.CONTENT,ActivationMode.HOT_RUNTIME,Map.of(),Set.of(),entries,List.of(),files,null);return ServerPackageRuntime.get(server).importOwned(viewer,UUID.randomUUID(),parsed).packageId();
    }
    private static final String SOURCE="""
        {"format":"divzero-native-ui/1","view":{"id":"nativeScore","title":"原生内容包 · 计分","surface":"SCREEN","data":{"query":""},"root":{"id":"root","type":"column","style":"padding-all: 12; gap: 8;","children":[
        {"id":"heading","type":"label","text":"LDLib2 原生内容与实时计分"},
        {"id":"search","type":"input","bind":"query","style":"height: 24; width: 240;"},
        {"id":"save","type":"button","text":"更新计分标题","style":"height: 24; width: 160;","events":{"click":[{"op":"emit","action":"save"}]}},
        {"id":"score","type":"label","bindings":{"text":{"op":"json","args":[{"op":"get","args":[{"op":"get","args":[{"data":"live"},"data"]},"snapshot"]}]}}}
        ]}},"reads":{"score":{"action":"scoreview.read","arguments":{},"result":"live","intervalTicks":20}},"actions":{"save":{"action":"scoreview.patch","arguments":{"expectedViewRevision":{"op":"get","args":[{"op":"get","args":[{"data":"live"},"data"]},"viewRevision"]},"patch":{"literal":{"title":"Native applied"}}},"result":"saved"}}}
        """;
    private static void fail(Throwable error){if(done)return;done=true;try{Files.writeString(root().resolve("failure.json"),JSON.writeValueAsString(Map.of("stage",stage,"error",error.toString(),"evidence",evidence)));}catch(Exception ignored){}Minecraft.getInstance().stop();}
    private NativePackageSmokeClient(){}
}
