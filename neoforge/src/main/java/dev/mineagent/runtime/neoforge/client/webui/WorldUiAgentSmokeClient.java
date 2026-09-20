package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.neoforge.ui.WorldUiAgentSmokeServer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;

/** Actual Native/GUI admission. The localhost planner owns subsequent AGENT RPC actions. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class WorldUiAgentSmokeClient {
    private static final Gson JSON=new Gson();private static int ticks,phase,after;private static boolean backup,trustPressed,trusted,seeded,busy,captured;
    private static String sourceView,agentView;private static Session source,delegated;private static JsonObject probe;private static boolean cancelStarted;
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve(WorldUiAgentSmokeServer.directory());}
    private static void main(String code){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+code+"})();",h.browser().getURL(),0);}
    public static void accept(JsonObject value){probe=value;}
    private static void submit(String goal){main("const c=document.querySelector('[data-world-delegation-view]');if(!c)return;c.querySelector('[data-field=agent]').value="+JSON.toJson(WorldUiAgentSmokeServer.agent.toString())+";const goal=c.querySelector('[data-field=goal]');goal.value="+JSON.toJson(goal)+";goal.dispatchEvent(new Event('input',{bubbles:true}));const e=c.querySelector('[data-field=expected]');e.value="+JSON.toJson(JSON.toJson(Map.of("equals",Map.of("/label","AGENT_CONTROL_OK","/count",1,"/actor",WorldUiAgentSmokeServer.agent.toString()),"text","AGENT_CONTROL_OK")))+";e.dispatchEvent(new Event('input',{bubbles:true}));const check=c.querySelector('[data-field=consent]');if(!check.checked)check.click();c.querySelector('[data-action=delegate-world]').click();");}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!WorldUiAgentSmokeServer.enabled()||phase==99)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;Files.createDirectories(root());
        if(ticks%40==0)Files.writeString(root().resolve("progress.json"),JSON.toJson(Map.of("phase",phase,"ticks",ticks,"probe",probe==null?new JsonObject():probe,"ready",host.ready(),"screen",mc.screen==null?"NONE":mc.screen.getClass().getSimpleName())));
        if(phase==98){if(ticks>=after){phase=99;mc.stop();}return;}
        if(WorldUiAgentSmokeServer.failure!=null||ticks>6500){Files.writeString(root().resolve("client-failure.json"),JSON.toJson(Map.of("phase",phase,"error",String.valueOf(WorldUiAgentSmokeServer.failure),"probe",probe==null?new JsonObject():probe)));host.close();mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("World UI Agent fixture failed"));phase=98;after=ticks+20;return;}
        if(phase==9){if(ticks>=after){phase=99;if(!WorldUiAgentSmokeServer.cancelMode().isEmpty())dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_WORLD_UI_AGENT_CANCEL_OK mode={} run={}",WorldUiAgentSmokeServer.cancelMode(),WorldUiAgentSmokeServer.RUN);if(WorldUiAgentSmokeServer.realModel())dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_WORLD_UI_AGENT_MODEL_OK model={} run={}",System.getProperty("mineagent.worldUiAgentModel"),WorldUiAgentSmokeServer.RUN);dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_WORLD_UI_AGENT_OK run={}",WorldUiAgentSmokeServer.RUN);mc.stop();}return;}
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen s){backup=true;var f=s.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(s)).proceed(false,false);}
        if(mc.player!=null&&!trusted){
            if(mc.screen instanceof dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen screen&&!trustPressed)for(var child:screen.children())if(child instanceof net.minecraft.client.gui.components.Button b&&b.active&&b.getMessage().getString().equals("信任此服务器")){b.onPress(new net.minecraft.client.input.InputWithModifiers(){public int input(){return org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;}public int modifiers(){return 0;}});trustPressed=true;break;}
            String fingerprint=dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot().values().getOrDefault("security.identityFingerprint","");
            if(trustPressed&&new dev.mineagent.runtime.client.trust.ServerTrustStore(mc.gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties")).status("local-integrated",fingerprint)==dev.mineagent.runtime.client.trust.TrustStatus.TRUSTED){trusted=true;mc.setScreen(null);}else return;
        }
        if(!seeded&&WorldUiAgentSmokeServer.ready&&mc.player!=null){
            var scope=new dev.mineagent.runtime.core.ui.PackageUiStateStore.Scope("local-integrated",WorldUiAgentSmokeServer.worldId,mc.player.getUUID(),WorldUiAgentSmokeServer.packageId,"ui/index.html","world:"+WorldUiAgentSmokeServer.first);
            try(var store=new dev.mineagent.runtime.core.ui.PackageUiStateStore(mc.gameDirectory.toPath().resolve("mineagent-runtime-data/client-package-ui-state.db"),java.time.Clock.systemUTC())){store.put(scope,"privateMarker",0,WorldUiAgentSmokeServer.revision,JSON.toJson("PLAYER_ONLY_LOCAL_SECRET"),()->true);}seeded=true;
        }
        if(phase==0&&seeded&&dev.mineagent.runtime.neoforge.client.objects.RuntimeObjectRenderer.drawn.getOrDefault(WorldUiAgentSmokeServer.firstEntity,0)>10){var object=mc.level.getEntity(WorldUiAgentSmokeServer.firstEntity);mc.gameMode.interact(mc.player,object,new net.minecraft.world.phys.EntityHitResult(object,object.position()),net.minecraft.world.InteractionHand.MAIN_HAND);phase=1;after=ticks+35;}
        if(host.ready()&&ticks%10==0)main("const c=document.querySelector('[data-world-delegation-view]');window.mineagentQuery({request:JSON.stringify({channel:'worldUiAgentProbe',form:!!c,status:c?.querySelector('[role=status]')?.textContent||'',views:[...document.querySelectorAll('.window')].map(n=>({id:n.dataset.viewId,kind:n.dataset.contentKind,title:n.dataset.title}))}),persistent:false,onSuccess(){},onFailure(){}});");
        if(phase==1&&ticks>=after&&!busy&&PackageContentClient.worldViews().size()==1){sourceView=PackageContentClient.worldViews().getFirst();source=PackageContentClient.session(sourceView);if(source==null)return;busy=true;PackagePageAgent.inspectManagedView(sourceView).whenComplete((value,error)->mc.execute(()->{try{
            if(error!=null)throw new IllegalStateException(error);if(!value.contains("PLAYER_ONLY_SERVER_SECRET")||!value.contains("PLAYER_ONLY_LOCAL_SECRET")){busy=false;after=ticks+20;return;}Files.writeString(root().resolve("player-dom.json"),value);main("document.querySelector('[data-view-id=\""+sourceView+"\"] [data-action=open-world-delegation]').click();");phase=2;busy=false;
        }catch(Exception e){fail(e);}}));}
        if(phase==2&&probe!=null&&probe.get("form").getAsBoolean()){submit("将实际物件标签设置为 AGENT_CONTROL_OK 并点击保存，确认页面与服务端结果。");phase=3;}
        if(phase==3&&probe!=null&&probe.get("status").getAsString().equals("WORLD_UI_AGENT_OUT_OF_REACH")){Files.writeString(root().resolve("far-gui.json"),JSON.toJson(probe));if(PackageContentClient.session(sourceView)==null)throw new IllegalStateException("FAR_REJECTION_CONSUMED_PLAYER");WorldUiAgentSmokeServer.bringNear=true;phase=4;after=ticks+30;}
        if((phase==3||phase==5)&&probe!=null){String status=probe.get("status").getAsString();if(status.contains("UI_REQUEST_REJECTED")||status.equals("UI_SERVER_OPERATION_FAILED")||status.equals("EXPLICIT_UI_DELEGATION_REQUIRED")||status.equals("FORBIDDEN")||status.equals("OPERATION_FAILED"))fail(new IllegalStateException("WORLD_AGENT_GUI_REJECTED: "+status));}
        if(phase==4&&WorldUiAgentSmokeServer.near&&ticks>=after){
            if(WorldUiAgentSmokeServer.queuedMode()){WorldUiAgentSmokeServer.queueBlocker=true;if(!Files.exists(mc.gameDirectory.toPath().resolve("world-ui-agent-blocker-pending.json")))return;}
            submit("身体已由测试场景放到物件旁。将实际物件标签设置为 AGENT_CONTROL_OK 并点击保存，确认页面与服务端结果。");phase=5;}
        if(phase==5){for(String v:PackageContentClient.worldViews()){var s=PackageContentClient.rawSession(v);if(s!=null&&s.binding().actorKind()==ActorKind.AGENT){agentView=v;delegated=s;}}
            if(delegated!=null&&!cancelStarted&&!WorldUiAgentSmokeServer.cancelMode().isEmpty()&&(WorldUiAgentSmokeServer.queuedMode()?WorldUiAgentSmokeServer.queuedReady:Files.exists(mc.gameDirectory.toPath().resolve("world-ui-agent-provider-pending.json")))){
                cancelStarted=true;String selector=switch(WorldUiAgentSmokeServer.cancelMode()){case "hide"->"button[aria-label=\"收起\"]";case "close"->"button[aria-label=\"关闭\"]";case "stop","queued-stop"->"[data-action=stop-world-agent]";default->"";};
                if(WorldUiAgentSmokeServer.cancelMode().equals("far"))WorldUiAgentSmokeServer.cancelFar=true;
                else if(WorldUiAgentSmokeServer.cancelMode().equals("queued-revoke"))WorldUiAgentSmokeServer.revokeQueued=true;
                else main("document.querySelector('[data-view-id=\""+agentView+"\"]')?.querySelector("+JSON.toJson(selector)+")?.click();");
                Files.writeString(root().resolve("cancel-client-intent.json"),JSON.toJson(Map.of("mode",WorldUiAgentSmokeServer.cancelMode(),"view",agentView,"source",sourceView,"session",delegated.sessionId(),"localProviderAlreadyInFlight",true)));
            }
            if(WorldUiAgentSmokeServer.verified&&delegated!=null&&!busy){
            if(agentView.equals(sourceView)||delegated.binding().actorId().equals(source.binding().actorId()))throw new IllegalStateException("WORLD_AGENT_REUSED_PLAYER_DOCUMENT");busy=true;
            UiClientSessions.contentRequest("command",delegated,"worldui.action",Map.of("name","rename","payload","{\"label\":\"LATE_SHOULD_NOT_APPLY\"}","expectedRevision","1"),UUID.randomUUID()).whenComplete((receipt,error)->mc.execute(()->{try{if(error!=null||receipt.code()!=Code.VIEW_NOT_RENDERED)throw new IllegalStateException("ENDED_AGENT_SESSION_WRITABLE",error);Files.writeString(root().resolve("late-rejected.json"),JSON.toJson(receipt));net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("agent-world-ui.png"));captured=true;}catch(Exception e){mc.execute(()->fail(e));}});phase=6;}catch(Exception e){fail(e);}}));
        }}
        if(phase==6&&captured){WorldUiAgentSmokeServer.finish=true;phase=7;}
        if(phase==7&&WorldUiAgentSmokeServer.done){Files.writeString(root().resolve("client-result.json"),JSON.toJson(Map.of("status",WorldUiAgentSmokeServer.cancelMode().isEmpty()?"WORLD_UI_AGENT_NATIVE_GUI_VERIFIED":"WORLD_UI_AGENT_CANCEL_NATIVE_GUI_VERIFIED","source",source,"agentSession",delegated,"freshDocument",true,"paidProviderUsed",WorldUiAgentSmokeServer.realModel(),"modelRequests",WorldUiAgentSmokeServer.modelRequests,"planner",WorldUiAgentSmokeServer.providerMode(),"fullV1",false,"cancellationMode",WorldUiAgentSmokeServer.cancelMode())));host.close();mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("World UI Agent verified"));phase=9;after=ticks+20;}
    }
    private static void fail(Exception e){WorldUiAgentSmokeServer.failure=e.getMessage();try{Files.writeString(root().resolve("callback-failure.json"),JSON.toJson(Map.of("phase",phase,"error",e.toString())));}catch(Exception ignored){}}
}
