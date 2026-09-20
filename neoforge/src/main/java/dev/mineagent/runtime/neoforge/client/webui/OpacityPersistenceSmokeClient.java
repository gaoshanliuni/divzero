package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.api.ui.UiCapture;
import dev.mineagent.runtime.client.webui.WebGuiRendererSettings;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import dev.mineagent.runtime.neoforge.network.MineAgentPayloads;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** New JVMs reopen the original signed preview and client preferences. Never delegates or replays a model operation. */
public final class OpacityPersistenceSmokeClient {
    private static final Gson JSON=new Gson();private static int phase,ticks,after;private static boolean busy,done;
    private static JsonObject basis,probe,restored;private static Session original,current;private static String view,childDocument;
    private static Object originalBrowser;private static UUID settingsRoot;
    public static boolean resuming(){return Boolean.getBoolean("mineagent.opacityPersistenceSmoke")&&!stage().equals("prepare");}
    public static String stage(){return System.getProperty("mineagent.opacityPersistenceStage","prepare");}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("opacity-persistence");}
    private static void save(String name,Object value)throws Exception{Files.createDirectories(root().resolve(stage()));Files.writeString(root().resolve(stage()).resolve(name),JSON.toJson(value));}
    private static void require(boolean ok,String code){if(!ok)throw new IllegalStateException(code);}
    public static void prepared(UUID packageId,long revision,double opacity,JsonObject layout,String scene)throws Exception{
        var session=UiClientSessions.current();require(session!=null,"OPACITY_PREPARE_SESSION");Files.createDirectories(root());
        Files.writeString(root().resolve("prepare.json"),JSON.toJson(Map.of("packageId",packageId,"packageRevision",revision,"opacity",opacity,"layout",layout,"rootSession",session,"scene",scene,"systemInputInjected",false,"realModelCalls",0)));
    }
    public static void accept(JsonObject n){if(n.has("opacityPersistenceProbe"))probe=n.getAsJsonObject("opacityPersistenceProbe");}
    private static void main(String script){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+script+"})();",h.browser().getURL(),0);}
    private static void probeSettings(){main("const c=document.querySelector('#renderer-choice'),s=document.querySelector('#renderer-save'),p=c?.closest('.content');window.mineagentQuery({request:JSON.stringify({channel:'worldUiSmokeProbe',opacityPersistenceProbe:{ready:!!c&&!c.disabled&&!!s&&!s.disabled,configured:document.querySelector('#renderer-configured')?.textContent||'',running:document.querySelector('#renderer-current')?.textContent||'',message:document.querySelector('#renderer-status')?.textContent||'',revision:p?.dataset.rendererRevision||'',pending:p?.dataset.rendererPending||''}}),persistent:false,onSuccess(){},onFailure(){}});");}
    private static String prefsKey(JsonObject layouts){String id=basis.get("packageId").getAsString();for(boolean candidate:new boolean[]{false,true}){String key=dev.mineagent.runtime.core.ui.UiViewSettings.layoutKey(UUID.fromString(id),"ui/index.html","",candidate);if(layouts.has(key))return key;}throw new IllegalStateException("OPACITY_PREFERENCE_MISSING");}
    private static JsonObject preference()throws Exception{String scope="integrated|"+current.binding().worldId()+"|"+current.binding().viewerPlayerId();Path file=Minecraft.getInstance().gameDirectory.toPath().resolve("mineagent-runtime-data/ui-state").resolve(UiCapture.sha256(scope.getBytes(StandardCharsets.UTF_8))+".json");var all=JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("layouts");return all.getAsJsonObject(prefsKey(all)).deepCopy();}
    private static double expected(){return stage().equals("reenabled")&&basis.get("opacity").getAsDouble()==0?1:basis.get("opacity").getAsDouble();}
    private static void validate(JsonObject layout,double alpha){
        require(layout.get("opacity").getAsDouble()==alpha&&layout.has("opacityPaint"),"OPACITY_NATIVE_RESTORE");var paint=layout.getAsJsonObject("opacityPaint");require(paint.get("status").getAsString().equals("NATIVE_ATLAS_PAINTED")&&paint.get("opacity").getAsDouble()==alpha,"OPACITY_NATIVE_PAINT");
        var b=layout.getAsJsonObject("bounds");var a=layout.getAsJsonObject("area");require(b.get("width").getAsDouble()==500&&b.get("height").getAsDouble()==360,"OPACITY_RESTORED_SIZE");
        if(!layout.get("source").getAsString().equals("PLAYER"))require(Math.abs(b.get("x").getAsDouble()-(a.get("x").getAsDouble()+a.get("width").getAsDouble()-524))<1&&Math.abs(b.get("y").getAsDouble()-a.get("y").getAsDouble()-12)<1,"OPACITY_RESTORED_ANCHOR");
    }
    @FunctionalInterface private interface Checked<T>{void accept(T value)throws Exception;}
    private static <T> void await(CompletableFuture<T> future,int next,Checked<T> check){busy=true;future.whenComplete((value,error)->Minecraft.getInstance().execute(()->{try{if(error!=null)throw new IllegalStateException(error);check.accept(value);phase=next;after=ticks+15;busy=false;}catch(Exception failed){fail(failed);}}));}
    private static void step(int n){phase=n;after=ticks+15;}
    private static String rootMessage(Throwable e){for(int i=0;i<8&&e.getCause()!=null;i++)e=e.getCause();return Objects.toString(e.getMessage(),e.getClass().getSimpleName());}
    public static void tick()throws Exception{
        if(done)return;ticks++;if(busy||ticks<after)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;
        try{require(Set.of("resume","legacy","reenabled").contains(stage()),"OPACITY_RESTORE_STAGE");require(ticks<2400,"OPACITY_RESTORE_TIMEOUT_"+phase);require(!Files.exists(mc.gameDirectory.toPath().resolve("live-placement-request-2.json")),"OPACITY_MODEL_REPLAYED");
            if(mc.player==null)return;if(ticks%20==0)ClientPacketDistributor.sendToServer(new MineAgentPayloads.PanelRequest());
            if(ticks%20==0&&host.ready()&&phase>=8)probeSettings();
            switch(phase){
                case 0->{basis=JsonParser.parseString(Files.readString(root().resolve("prepare.json"))).getAsJsonObject();original=JSON.fromJson(basis.get("rootSession"),Session.class);step(1);}
                case 1->{var values=dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot().values();if(!Boolean.parseBoolean(values.getOrDefault("runtime.initialized","false")))return;String fingerprint=values.getOrDefault("security.identityFingerprint","");require(new dev.mineagent.runtime.client.trust.ServerTrustStore(mc.gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties")).status("local-integrated",fingerprint)==dev.mineagent.runtime.client.trust.TrustStatus.TRUSTED,"OPACITY_RESTORE_TRUST_CHANGED");host.open();step(2);}
                case 2->{if(!host.ready()||UiClientSessions.current()==null)return;current=UiClientSessions.current();require(current.binding().worldId().equals(original.binding().worldId())&&current.binding().viewerPlayerId().equals(original.binding().viewerPlayerId())&&!current.sessionId().equals(original.sessionId())&&!current.serverInstanceId().equals(original.serverInstanceId()),"OPACITY_NEW_JVM_IDENTITY");require(WebGuiRendererSettings.liveAtlas(mc.gameDirectory.toPath().resolve("config/mineagent-webgui.properties"))!=stage().equals("legacy"),"OPACITY_RENDERER_FILE_STAGE");save("session.json",Map.of("current",current,"previous",original,"preferenceBefore",preference()));await(UiClientSessions.contentRequest("command",current,"shell.read",Map.of(),UUID.randomUUID()),22,r->{save("current-session-read.json",r);require(r.code()==Code.OBSERVED,"OPACITY_CURRENT_SESSION_READ_FAILED");});}
                case 22->await(UiClientSessions.contentRequest("command",original,"shell.read",Map.of(),UUID.randomUUID()),3,r->{save("old-session-rejected.json",r);require(!Set.of(Code.OK,Code.OBSERVED,Code.ACCEPTED,Code.IN_PROGRESS,Code.TIMEOUT).contains(r.code()),"OPACITY_OLD_SESSION_ACCEPTED");});
                case 3->{var opening=PackagePreviewClient.open(UUID.fromString(basis.get("packageId").getAsString()),basis.get("packageRevision").getAsLong());
                    if(stage().equals("legacy"))await(opening.handle((r,e)->e==null?"OPENED":rootMessage(e)),7,status->{require(status.equals("UI_OPACITY_BACKEND_REQUIRED"),"OPACITY_LEGACY_EXPOSED_PAGE_"+status);save("legacy-rejected.json",Map.of("status",status,"preferenceAfter",preference()));});
                    else await(opening,4,r->{require(r.code()==Code.ACCEPTED,"OPACITY_PREVIEW_REOPEN_FAILED");view=r.values().get("viewId");});}
                case 4->{var layout=view==null?null:UiPresentationClient.observe(view);if(layout==null||!host.packageLoaded(view)||!layout.has("opacityPaint")||layout.get("opacity").getAsDouble()!=expected())return;validate(layout,expected());require(!layout.get("hostDocumentId").equals(basis.getAsJsonObject("layout").get("hostDocumentId"))&&!layout.get("viewId").equals(basis.getAsJsonObject("layout").get("viewId")),"OPACITY_OLD_DOCUMENT_REUSED");restored=layout.deepCopy();save("restored.json",layout);await(PackagePageAgent.inspectManagedView(view),5,text->{var n=JsonParser.parseString(text).getAsJsonObject();childDocument=n.get("documentId").getAsString();save("document-before.json",n);});}
                case 5->{if(stage().equals("resume")&&basis.get("opacity").getAsDouble()==0){main("document.querySelector('[data-restore-opacity-view=\""+view+"\"]').click();");step(6);}else step(7);}
                case 6->{var layout=UiPresentationClient.observe(view);if(layout==null||!layout.has("opacityPaint")||layout.get("opacity").getAsDouble()!=1||!layout.get("source").getAsString().equals("PLAYER"))return;validate(layout,1);save("player-visible.json",layout);await(PackagePageAgent.inspectManagedView(view),7,text->{var n=JsonParser.parseString(text).getAsJsonObject();require(n.get("documentId").getAsString().equals(childDocument),"OPACITY_RECOVERY_RELOADED_DOCUMENT");save("document-after-visible.json",n);});}
                case 7->{if(stage().equals("reenabled")){step(10);break;}originalBrowser=host.browser();settingsRoot=WebGuiPaintComposition.matched().orElseThrow().frame().documentId();main("document.querySelector('#open-renderer-settings').click();");step(8);}
                case 8->{if(probe==null||!probe.get("ready").getAsBoolean())return;String wanted=stage().equals("legacy")?"LIVE_ATLAS":"LEGACY";save("settings-before.json",probe);main("document.querySelector('#renderer-choice input[value="+wanted+"]').click();document.querySelector('#renderer-save').click();");probe=null;step(9);}
                case 9->{String wanted=stage().equals("legacy")?"LIVE_ATLAS":"LEGACY",running=stage().equals("legacy")?"LEGACY":"LIVE_ATLAS";if(probe==null||!probe.get("ready").getAsBoolean()||!probe.get("configured").getAsString().equals("已保存："+wanted))return;require(probe.get("running").getAsString().equals("当前宿主："+running)&&probe.get("pending").getAsString().equals("true")&&host.browser()==originalBrowser&&WebGuiPaintComposition.matched().orElseThrow().frame().documentId().equals(settingsRoot),"RENDERER_SAVE_REBUILT_BROWSER");save("settings-saved.json",probe);save("renderer-save-runtime.json",Map.of("sameBrowser",true,"sameRootDocument",true,"configured",wanted,"running",running,"snapshot",WebGuiAtlasCompositor.snapshot()));if(view!=null){var layout=UiPresentationClient.observe(view);validate(layout,stage().equals("resume")&&basis.get("opacity").getAsDouble()==0?1:expected());save("after-settings-layout.json",layout);}step(10);}
                case 10->{busy=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve(stage()).resolve("result.png"));mc.execute(()->{busy=false;step(11);});}catch(Exception e){mc.execute(()->fail(e));}});}
                case 11->{save("result.json",Map.of("status","OPACITY_PERSISTENCE_STAGE_VERIFIED","stage",stage(),"rootSession",current,"preferenceAfter",preference(),"systemInputInjected",false,"newModelRequests",0,"fullV1",false));done=true;host.close();mc.stop();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_OPACITY_PERSISTENCE_{}_OK",stage().toUpperCase(Locale.ROOT));}
                default->throw new IllegalStateException("OPACITY_RESTORE_PHASE");
            }
        }catch(Exception e){fail(e);}
    }
    private static void fail(Exception e){if(done)return;done=true;try{save("failure.json",Map.of("phase",phase,"error",e.toString()));}catch(Exception ignored){}WebGuiHostAdapter.INSTANCE.close();Minecraft.getInstance().stop();throw new IllegalStateException("OPACITY_PERSISTENCE_FAILED",e);}
}
