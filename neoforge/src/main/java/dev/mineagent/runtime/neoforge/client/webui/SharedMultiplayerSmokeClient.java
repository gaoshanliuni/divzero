package dev.mineagent.runtime.neoforge.client.webui;

import com.google.gson.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.nio.file.*;
import java.util.*;

/** Real play-protocol/CEF fixture. Does not read server static data, use Robot, or write database state. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class SharedMultiplayerSmokeClient {
    private static final Gson JSON=new Gson();private static final String ROLE=System.getProperty("mineagent.sharedMultiplayerRole","");
    private static String run="startup",view,shot,retriedNote;private static JsonObject info,probe,savedEnter,savedReceipt;private static Request enterWire;
    private static Session first,fresh;private static Receipt oldDenied,foreignDenied;
    private static int ticks,phase,after,clearTicks,shotNext;private static boolean trusted,trustPressed,openSent,busy,ended,refreshed,foreignSent;
    private SharedMultiplayerSmokeClient(){}
    public static boolean enabled(){return Set.of("A","B").contains(ROLE);}
    public static boolean accept(UiPayloads.Event p){
        if(!enabled())return false;
        try{
            String other=ROLE.equals("A")?"B":"A";
            if(!run.equals("startup")&&(p.json().contains("PRIVATE_NOTE_"+other+"_"+run)||ROLE.equals("B")&&p.json().contains("OWNER_ONLY_"+run)))throw new IllegalStateException("SHARED_MULTI_PRIVATE_PACKET_LEAK");
            if(p.channel().equals("sharedMultiFixture")){info=JsonParser.parseString(p.json()).getAsJsonObject();String value=info.get("run").getAsString();if(!run.equals("startup")&&!run.equals(value))throw new IllegalStateException("SHARED_MULTI_RUN_CHANGED");run=value;return true;}
            if(phase==13&&enterWire!=null&&p.channel().equals("receipt")&&p.requestId().equals(enterWire.operationId())){oldDenied=JSON.fromJson(p.json(),Receipt.class);return true;}
            return false;
        }catch(Exception e){fail(e);return true;}
    }
    public static void sent(String channel,Request request){if(enabled()&&enterWire==null&&channel.equals("command")&&request.action().equals("worldui.action")&&request.arguments().getOrDefault("name","").equals("enter"))enterWire=request;}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("shared-multiplayer-evidence").resolve(run);}
    private static void write(String name,Object data)throws Exception{Files.createDirectories(root());Files.writeString(root().resolve(name),JSON.toJson(data));}
    private static void control(String action){ClientPacketDistributor.sendToServer(new UiPayloads.Command(UUID.randomUUID(),"sharedMultiFixture",JSON.toJson(Map.of("action",action))));}
    private static void frame(String source){var host=WebGuiHostAdapter.INSTANCE;String url=host.packageUrl(view);for(long id:host.browser().getFrameIdentifiers()){var f=host.browser().getFrame(id);if(f!=null&&!f.isMain()&&url.equals(f.getURL())){f.executeJavaScript(source,url,0);return;}}throw new IllegalStateException("SHARED_MULTI_FRAME_MISSING");}
    private static String note(){return "PRIVATE_NOTE_"+ROLE+"_"+run;}
    private static JsonObject data(){return probe.getAsJsonObject("current").getAsJsonObject("data");}
    private static JsonObject values(){return data().getAsJsonObject("shared").getAsJsonObject("values");}
    private static boolean has(String key){return probe!=null&&probe.has(key)&&!probe.get(key).isJsonNull();}
    private static void inspect(){
        if(busy||view==null)return;busy=true;frame("window.sharedProbe?.();");
        PackagePageAgent.inspectManagedView(view).whenComplete((value,error)->Minecraft.getInstance().execute(()->{try{
            if(error!=null)throw new IllegalStateException(error);var text=JsonParser.parseString(value).getAsJsonObject().get("visibleText").getAsString();int i=text.indexOf("SHARED_MULTI_PROOF:");
            if(i>=0){probe=JsonParser.parseString(text.substring(i+19)).getAsJsonObject();write("last-probe.json",probe);if(!probe.get("error").getAsString().isEmpty())throw new IllegalStateException(probe.get("error").getAsString());
                if(text.contains("PRIVATE_NOTE_"+(ROLE.equals("A")?"B":"A")+"_"+run)||ROLE.equals("B")&&text.contains("OWNER_ONLY_"+run))throw new IllegalStateException("SHARED_MULTI_PRIVATE_DOM_LEAK");}
            busy=false;
        }catch(Exception e){fail(e);}}));
    }
    private static void requireOwnSnapshot(boolean completed){
        var mc=Minecraft.getInstance();if(!has("current")||!data().get("actor").getAsString().equals(mc.player.getUUID().toString()))throw new IllegalStateException("SHARED_MULTI_ACTOR_PROJECTION");
        if(ROLE.equals("B")&&values().has("ownerNote"))throw new IllegalStateException("SHARED_MULTI_OWNER_FIELD_LEAK");
        if(completed&&(!values().get("note").getAsString().equals(note())||values().get("count").getAsInt()!=1||data().getAsJsonObject("shared").get("revision").getAsInt()!=5))throw new IllegalStateException("SHARED_MULTI_SNAPSHOT_MISMATCH");
    }
    private static void beginShot(String name,int next){frame("document.querySelector('#proof').textContent='';");shot=name;shotNext=next;phase=80;after=ticks+20;}
    private static void disconnect(){WebGuiHostAdapter.INSTANCE.close();Minecraft.getInstance().disconnectFromWorld(net.minecraft.network.chat.Component.literal("Shared-state actual reconnect"));trusted=false;trustPressed=false;openSent=false;clearTicks=0;view=null;probe=null;phase=10;after=ticks+60;}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!enabled()||ended)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;
        try{
            if(ticks>14000)throw new IllegalStateException("SHARED_MULTI_CLIENT_TIMEOUT phase="+phase);
            if(mc.player!=null&&ticks%20==0){control("info");write("progress.json",Map.of("phase",phase,"trusted",trusted,"trustPressed",trustPressed,"initializedSnapshot",dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot().values().getOrDefault("runtime.initialized","false"),"screen",mc.screen==null?"NONE":mc.screen.getClass().getName(),"hasView",view!=null,"ready",info!=null&&info.get("ready").getAsBoolean()));}
            if(mc.player!=null&&!trusted&&info!=null&&info.get("ready").getAsBoolean()&&ticks%40==0)ClientPacketDistributor.sendToServer(new dev.mineagent.runtime.neoforge.network.MineAgentPayloads.PanelRequest());
            if(phase==10&&mc.getConnection()==null&&ticks>=after){String address="127.0.0.1:"+System.getProperty("mineagent.sharedMultiplayerPort","25591");ConnectScreen.startConnecting(new TitleScreen(),mc,ServerAddress.parseString(address),new ServerData("Shared state fixture",address,ServerData.Type.OTHER),false,null);phase=11;}
            if(mc.player!=null&&!trusted){
                if(mc.screen instanceof dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen screen&&!trustPressed)for(var child:screen.children())if(child instanceof net.minecraft.client.gui.components.Button b&&b.active&&b.getMessage().getString().equals("信任此服务器")){b.onPress(new net.minecraft.client.input.InputWithModifiers(){public int input(){return org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;}public int modifiers(){return 0;}});trustPressed=true;ClientPacketDistributor.sendToServer(new dev.mineagent.runtime.neoforge.network.MineAgentPayloads.PanelRequest());break;}
                var snapshot=dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot();String fingerprint=snapshot.values().getOrDefault("security.identityFingerprint","");String serverId=mc.getCurrentServer()==null?"":mc.getCurrentServer().ip;
                if(Boolean.parseBoolean(snapshot.values().getOrDefault("runtime.initialized","false"))&&new dev.mineagent.runtime.client.trust.ServerTrustStore(mc.gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties")).status(serverId,fingerprint)==dev.mineagent.runtime.client.trust.TrustStatus.TRUSTED){trusted=true;if(trustPressed&&mc.screen instanceof dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen)mc.setScreen(null);write("trust-"+phase+".json",Map.of("serverId",serverId,"fingerprint",fingerprint,"nativeTrustButtonPressed",trustPressed));}
            }
            if(phase==0||phase==11){
                clearTicks=trusted&&mc.screen==null?clearTicks+1:0;
                if(info!=null&&info.get("ready").getAsBoolean()&&clearTicks>=20&&!openSent){var entity=mc.level.getEntity(UUID.fromString(info.get("object").getAsString()));if(entity!=null){mc.gameMode.interact(mc.player,entity,new net.minecraft.world.phys.EntityHitResult(entity,entity.position().add(0,.8,0)),net.minecraft.world.InteractionHand.MAIN_HAND);openSent=true;}}
                if(openSent&&host.ready()&&PackageContentClient.worldViews().size()==1){String candidate=PackageContentClient.worldViews().getFirst();var session=PackageContentClient.session(candidate);if(session!=null){view=candidate;if(phase==0){first=session;phase=1;}else{fresh=session;if(fresh.sessionId().equals(first.sessionId()))throw new IllegalStateException("SHARED_MULTI_OLD_SESSION_REUSED");phase=12;}after=ticks+20;write("session-"+phase+".json",session);}}
            }
            if(view!=null&&phase!=80&&phase<90&&ticks%20==0&&!busy)inspect();
            if(phase==1&&ticks>=after&&has("current")){requireOwnSnapshot(false);if(data().getAsJsonObject("shared").get("revision").getAsInt()!=2||values().get("count").getAsInt()!=0)throw new IllegalStateException("SHARED_MULTI_BARRIER_SNAPSHOT");write("before-barrier.json",probe);control("ready");phase=2;}
            if(phase==2&&info.get("goAt").getAsLong()>0){frame("window.sharedStartAt("+info.get("goAt").getAsLong()+","+JSON.toJson("ENTRY_"+ROLE)+");");phase=3;}
            if(phase==3&&has("enterReply")){requireOwnSnapshot(false);savedEnter=probe.getAsJsonObject("enterRequest").deepCopy();savedReceipt=probe.getAsJsonObject("enterReply").getAsJsonObject("data").getAsJsonObject("transaction").deepCopy();if(!Set.of("APPLIED","CONFLICT").contains(savedReceipt.get("status").getAsString())||enterWire==null)throw new IllegalStateException("SHARED_MULTI_REAL_ENTER_MISSING");write("enter.json",Map.of("probe",probe,"wire",enterWire));frame("window.sharedReplay("+JSON.toJson(savedEnter)+").catch(e=>window.sharedError=e.message);");phase=4;}
            if(phase==4&&has("replayed")){if(!savedReceipt.equals(probe.getAsJsonObject("replayed").getAsJsonObject("data").getAsJsonObject("transaction")))throw new IllegalStateException("SHARED_MULTI_ENTER_REPLAY_CHANGED");frame("const n=document.querySelector('#note');n.value="+JSON.toJson(note())+";n.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#save-note').click();");phase=5;}
            if(phase==5&&has("noteReply")&&has("current")){
                var receipt=probe.getAsJsonObject("noteReply").getAsJsonObject("data").getAsJsonObject("transaction");String status=receipt.get("status").getAsString();
                if(status.equals("CONFLICT")&&data().getAsJsonObject("shared").get("revision").getAsLong()>=receipt.get("revision").getAsLong()){String op=probe.getAsJsonObject("noteRequest").get("operationId").getAsString();if(retriedNote==null){retriedNote=op;write("note-explicit-cas-retry.json",probe);frame("document.querySelector('#save-note').click();");}else if(!retriedNote.equals(op))throw new IllegalStateException("SHARED_MULTI_REPEATED_NOTE_CONFLICT");}
                else if(status.equals("APPLIED")&&values().has("note")){if(!values().get("note").getAsString().equals(note()))throw new IllegalStateException("SHARED_MULTI_NOTE_NOT_SAVED");control("submitted");phase=6;}
            }
            if(phase==6&&info.get("bothSubmitted").getAsBoolean()){
                if(ROLE.equals("B")&&!foreignSent){foreignSent=true;UiClientSessions.contentRequest("command",first,"worldui.action",Map.of("name","enter","payload","{\"value\":\"FORGED_OTHER_ACTOR_REPLAY\"}","expectedRevision",enterWire.arguments().get("expectedRevision")),UUID.fromString(info.get("otherEnterOperation").getAsString())).whenComplete((r,error)->mc.execute(()->{if(error!=null)fail(new IllegalStateException(error));else foreignDenied=r;}));}
                if(ROLE.equals("A")||foreignDenied!=null){if(foreignDenied!=null){if(Set.of(Code.APPLIED,Code.OBSERVED,Code.ACCEPTED,Code.IN_PROGRESS).contains(foreignDenied.code()))throw new IllegalStateException("SHARED_MULTI_FOREIGN_REPLAY_ACCEPTED");write("foreign-rejected.json",foreignDenied);}frame("document.querySelector('#test-cas').click();");phase=61;}
            }
            if(phase==61&&has("casReply")&&has("current")){requireOwnSnapshot(true);var cas=probe.getAsJsonObject("casReply").getAsJsonObject("data").getAsJsonObject("transaction");if(!cas.get("status").getAsString().equals("CONFLICT")||!cas.get("error").getAsString().equals("SHARED_REVISION_CONFLICT"))throw new IllegalStateException("SHARED_MULTI_STALE_CAS_ACCEPTED");write("stale-cas-rejected.json",probe);control("probed");phase=62;}
            if(phase==62&&info.get("bothProbed").getAsBoolean()){frame("window.sharedRefresh().catch(e=>window.sharedError=e.message);");phase=7;after=ticks+30;}
            if(phase==7&&ticks>=after&&has("current")){requireOwnSnapshot(true);write("before-reconnect.json",Map.of("session",first,"probe",probe));if(ROLE.equals("A"))beginShot("before-reconnect",9);else phase=20;}
            if(phase==9)disconnect();
            if(phase==12&&ticks>=after&&has("current")){requireOwnSnapshot(true);phase=13;ClientPacketDistributor.sendToServer(new UiPayloads.Command(enterWire.operationId(),"command",JSON.toJson(enterWire)));}
            if(phase==13&&oldDenied!=null){if(!Set.of(Code.VIEW_NOT_RENDERED,Code.STALE_VIEW,Code.EXPIRED,Code.PERMISSION_DENIED).contains(oldDenied.code()))throw new IllegalStateException("SHARED_MULTI_OLD_SESSION_NOT_REJECTED");write("old-session-rejected.json",oldDenied);frame("window.sharedReplay("+JSON.toJson(savedEnter)+").catch(e=>window.sharedError=e.message);");phase=14;}
            if(phase==14&&has("replayed")){requireOwnSnapshot(true);if(!savedReceipt.equals(probe.getAsJsonObject("replayed").getAsJsonObject("data").getAsJsonObject("transaction")))throw new IllegalStateException("SHARED_MULTI_RECONNECT_REPLAY_CHANGED");write("reconnected.json",Map.of("firstSession",first,"freshSession",fresh,"probe",probe,"oldDenied",oldDenied));beginShot("after-reconnect",90);}
            if(phase==20&&info.get("aDone").getAsBoolean()&&!info.get("aOnline").getAsBoolean()){frame("window.sharedRefresh().catch(e=>window.sharedError=e.message);");phase=21;after=ticks+30;}
            if(phase==21&&ticks>=after&&has("current")){requireOwnSnapshot(true);if(!PackageContentClient.session(view).sessionId().equals(first.sessionId()))throw new IllegalStateException("SHARED_MULTI_B_SESSION_DISRUPTED");write("survived-a-disconnect.json",Map.of("session",first,"probe",probe));beginShot("survived-a",90);}
            if(phase==80&&ticks>=after&&!busy){busy=true;PackagePageAgent.captureManagedView(view).whenComplete((capture,error)->mc.execute(()->{try{if(error!=null)throw new IllegalStateException(error);Files.write(root().resolve(shot+"-private.png"),capture.png());net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve(shot+"-game.png"));mc.execute(()->{phase=shotNext;busy=false;});}catch(Exception e){mc.execute(()->fail(e));}});}catch(Exception e){fail(e);}}));}
            if(phase==90){write("result.json",Map.of("role",ROLE,"run",run,"status","REAL_CLIENT_SHARED_STATE_VERIFIED","providerCalls",0,"systemInputInjected",false,"reconnected",ROLE.equals("A")));control("done");phase=91;after=ticks+30;}
            if(phase==91&&ticks>=after){ended=true;host.close();mc.stop();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_SHARED_MULTI_CLIENT_OK role={}",ROLE);}
        }catch(Exception e){fail(e);}
    }
    private static void fail(Exception e){if(ended)return;ended=true;try{write("failure.json",Map.of("phase",phase,"error",e.toString(),"screen",Minecraft.getInstance().screen==null?"NONE":Minecraft.getInstance().screen.getClass().getName(),"probe",probe==null?new JsonObject():probe));}catch(Exception ignored){}WebGuiHostAdapter.INSTANCE.close();Minecraft.getInstance().stop();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.error("SHARED_MULTI_CLIENT_FAILED role={} phase={} error={}",ROLE,phase,e.toString());}
}
