package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.api.ui.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.nio.file.*;
import java.util.*;

/** Real clients and UI controls; no server static data or OS input injection. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class DeliverySmokeClient {
    private static final String ROLE=System.getProperty("mineagent.deliverySmokeRole","");private static final Gson JSON=new Gson();private static JsonObject info,probe;private static String run="startup",view,document;private static Session session;
    private static int ticks,phase,after;private static boolean trusted,pressed,busy,ended;private static Receipt closedRead;private static UUID feedbackOp,feedbackId;private static Map<String,String> feedbackBody;private static int feedbackNegative;
    private static boolean feedbackMode(){return Boolean.getBoolean("mineagent.feedbackSmoke");}
    private static boolean dataMode(){return Boolean.getBoolean("mineagent.feedbackData");}
    private static boolean wakeMode(){return Boolean.getBoolean("mineagent.feedbackWakeSmoke");}
    private static boolean cancelWake(){return Boolean.getBoolean("mineagent.feedbackWakeCancel");}
    private static boolean replySeen,goalDraftReported;
    public static boolean enabled(){return Set.of("A","B").contains(ROLE);}
    public static void openObserved(Session shell,DeliveryProtocol.Launch launch,Session content){if(!enabled())return;try{write("delivery-acceptance.json",Map.of("shell",shell,"launch",launch,"content",content));}catch(Exception e){fail(e);}}
    public static void openFailed(Throwable error){if(enabled())fail(new IllegalStateException("DELIVERY_OPEN_FAILURE",error));}
    public static boolean accept(UiPayloads.Event p){if(!enabled()||!p.channel().equals("deliveryFixture"))return false;info=JsonParser.parseString(p.json()).getAsJsonObject();run=info.get("run").getAsString();return true;}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("delivery-evidence").resolve(run);}
    private static void write(String name,Object value)throws Exception{Files.createDirectories(root());Files.writeString(root().resolve(name),JSON.toJson(value));}
    private static void control(String action){ClientPacketDistributor.sendToServer(new UiPayloads.Command(UUID.randomUUID(),"deliveryFixture",JSON.toJson(Map.of("action",action))));}
    private static void main(String source){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+source+"})();",h.browser().getURL(),0);}
    private static void frame(String source){var h=WebGuiHostAdapter.INSTANCE;String url=h.packageUrl(view);for(long id:h.browser().getFrameIdentifiers()){var f=h.browser().getFrame(id);if(f!=null&&!f.isMain()&&Objects.equals(url,f.getURL())){f.executeJavaScript("(()=>{"+source+"})();",url,0);return;}}throw new IllegalStateException("DELIVERY_FIXTURE_FRAME");}
    private static void inspect(){if(busy)return;busy=true;frame("window.deliveryProbe?.();"+(wakeMode()||dataMode()?"document.querySelector('#proof').scrollIntoView({block:'nearest'});":""));PackagePageAgent.inspectManagedView(view).whenComplete((value,error)->Minecraft.getInstance().execute(()->{busy=false;try{if(error!=null){after=ticks+20;return;}var n=JsonParser.parseString(value).getAsJsonObject();String text=n.get("visibleText").getAsString();int i=text.indexOf("DELIVERY_PROOF:");if(i>=0){probe=JsonParser.parseString(text.substring(i+15)).getAsJsonObject();if(document==null&&n.has("documentId"))document=n.get("documentId").getAsString();else if(document!=null&&n.has("documentId")&&!document.equals(n.get("documentId").getAsString()))throw new IllegalStateException("DELIVERY_REFRESH_RELOADED_DOCUMENT");write("last-probe.json",Map.of("observation",n,"data",probe));}}catch(Exception e){fail(e);}}));}
    private static void snapshot(int next){busy=true;frame("document.querySelector('#proof').textContent='';"+(feedbackMode()?"window.scrollTo(0,0);":""));PackagePageAgent.captureManagedView(view).whenComplete((capture,error)->Minecraft.getInstance().execute(()->{try{if(error!=null){busy=false;after=ticks+20;return;}Files.write(root().resolve("updated-private.png"),capture.png());net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("updated-game.png"));Minecraft.getInstance().execute(()->{phase=next;busy=false;control("updated");});}catch(Exception e){fail(e);}});}catch(Exception e){fail(e);}}));}
    private static void feedbackNegative(){
        if(busy)return;var mc=Minecraft.getInstance();busy=true;String action="feedback.submit";UUID op=UUID.randomUUID();var args=new LinkedHashMap<>(feedbackBody);int step=feedbackNegative;
        if(step==0)args.put("author",info.get("otherDelivery").getAsString());
        if(step==1)args.put("documentId","stale-"+document);
        if(step==2){action="feedback.read";args.clear();args.put("feedbackId",info.get("otherFeedbackId").getAsString());}
        if(step==3)op=feedbackOp;
        UiClientSessions.contentRequest("command",session,action,args,op).whenComplete((r,error)->mc.execute(()->{try{
            if(error!=null)throw new IllegalStateException(error);write("feedback-native-check-"+step+".json",r);
            boolean accepted=Set.of(Code.APPLIED,Code.ACCEPTED,Code.OBSERVED,Code.IN_PROGRESS).contains(r.code());if(step==3?!accepted:accepted)throw new IllegalStateException("FEEDBACK_NEGATIVE_OR_REPLAY_"+step);
            busy=false;feedbackNegative++;if(feedbackNegative==4){main("document.querySelector('[data-view-id=\""+view+"\"] [aria-label=收起]').click();");phase=43;after=ticks+40;}
        }catch(Exception e){fail(e);}}));
    }
    private static void historyAfterClose(){
        busy=true;var mc=Minecraft.getInstance();UiClientSessions.contentRequest("command",session,"feedback.submit",feedbackBody,feedbackOp).whenComplete((denied,error)->mc.execute(()->{try{
            if(error!=null||Set.of(Code.APPLIED,Code.ACCEPTED,Code.OBSERVED,Code.IN_PROGRESS).contains(denied.code()))throw new IllegalStateException("FEEDBACK_CLOSED_REPLAY");write("feedback-closed-replay.json",denied);
            UiClientSessions.command("feedback.list",Map.of("deliveryId",info.getAsJsonObject("own").get("deliveryId").getAsString(),"offset","0"),UUID.randomUUID()).whenComplete((r,fail)->mc.execute(()->{try{
                if(fail!=null||r.code()!=Code.OBSERVED)throw new IllegalStateException("FEEDBACK_HISTORY_DENIED");var rows=JsonParser.parseString(r.values().get("feedback")).getAsJsonArray();if(rows.size()!=1||!rows.get(0).getAsJsonObject().get("feedbackId").getAsString().equals(feedbackId.toString()))throw new IllegalStateException("FEEDBACK_HISTORY_SCOPE");write("feedback-closed-history.json",r);
                main("document.querySelector('[data-delivery-id=\""+info.getAsJsonObject("own").get("deliveryId").getAsString()+"\"] [data-action=feedback-history]')?.click();");busy=false;phase=61;after=ticks+60;
            }catch(Exception e){fail(e);}}));
        }catch(Exception e){fail(e);}}));
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!enabled()||ended||ClientCodeDeliverySmokeClient.enabled())return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;try{
            if(FeedbackRestartSmokeClient.prepare(session,feedbackOp,feedbackId,feedbackBody))return;
            if(ticks>14000)throw new IllegalStateException("DELIVERY_FIXTURE_TIMEOUT_"+phase);
            if(feedbackMode()&&Set.of(40,41).contains(phase)&&info!=null&&info.has("feedback")&&Set.of("FAILED","INTERRUPTED","CANCELLED").contains(info.getAsJsonObject("feedback").get("state").getAsString()))throw new IllegalStateException("FEEDBACK_FIXTURE_CONSUMER_TERMINAL_"+info.getAsJsonObject("feedback").get("state").getAsString());
            if(mc.player!=null&&ticks%20==0){control("info");write("progress.json",Map.of("phase",phase,"trusted",trusted,"hostReady",host.ready()));}
            if(mc.player!=null&&!trusted){if(mc.screen instanceof dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen screen&&!pressed)for(var child:screen.children())if(child instanceof net.minecraft.client.gui.components.Button b&&b.active&&b.getMessage().getString().equals("信任此服务器")){b.onPress(new net.minecraft.client.input.InputWithModifiers(){public int input(){return org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;}public int modifiers(){return 0;}});pressed=true;break;}
                if(ticks%40==0)ClientPacketDistributor.sendToServer(new dev.mineagent.runtime.neoforge.network.MineAgentPayloads.PanelRequest());var snap=dev.mineagent.runtime.neoforge.network.PanelSnapshotInbox.snapshot();String fp=snap.values().getOrDefault("security.identityFingerprint","");if(mc.getCurrentServer()!=null&&Boolean.parseBoolean(snap.values().getOrDefault("runtime.initialized","false"))&&new dev.mineagent.runtime.client.trust.ServerTrustStore(mc.gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties")).status(mc.getCurrentServer().ip,fp)==dev.mineagent.runtime.client.trust.TrustStatus.TRUSTED){trusted=true;if(mc.screen instanceof dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen)mc.setScreen(null);after=ticks+60;}}
            if(GenerationRepairSmokeClient.tick(trusted,info))return;
            if(FeedbackApplicationSmokeClient.tick(trusted,info))return;
            if(FeedbackRestartSmokeClient.resume(trusted,info))return;
            if(phase==0&&trusted&&info!=null&&info.get("ready").getAsBoolean()&&ticks>=after){if(host.browser()!=null||!ContentDeliveryClient.views().isEmpty())throw new IllegalStateException("DELIVERY_OPENED_WITHOUT_ACCEPTANCE");write("before-accept.json",Map.of("hostAbsent",true,"info",info));host.open();phase=1;}
            if(phase==1&&host.ready()&&UiClientSessions.current()!=null){main("document.querySelector('#open-deliveries').click();");phase=2;after=ticks+30;}
            if(phase==2&&ticks>=after&&ticks%20==0){String id=info.getAsJsonObject("own").get("deliveryId").getAsString();main("const b=document.querySelector('[data-delivery-id=\""+id+"\"] [data-action=delivery-open]');if(b&&!b.disabled&&!window.__deliveryOpened){window.__deliveryOpened=true;b.click();}");var list=ContentDeliveryClient.views();if(list.size()==1){view=list.getFirst();session=PackageContentClient.session(view);phase=3;after=ticks+30;}}
            if(view!=null&&(Set.of(3,31,40,41).contains(phase)||phase==4&&!cancelWake()||phase==5&&wakeMode()&&!cancelWake()&&ROLE.equals("B")&&host.packageUrl(view)!=null)&&ticks>=after&&ticks%20==0&&!busy)inspect();
            if(phase==3&&probe!=null&&probe.has("state")&&!probe.get("state").isJsonNull()&&info.getAsJsonObject("own").get("status").getAsString().equals("RENDERED")){
                if(ROLE.equals("B")){String id=info.get("rejectId").getAsString();main("document.querySelector('[data-delivery-id=\""+id+"\"] [data-action=delivery-reject]')?.click();");}
                frame("const f=document.querySelector('#draft');f.value='KEEP_DRAFT_"+ROLE+"';f.dispatchEvent(new Event('input',{bubbles:true}));");write("paint-confirmed.json",Map.of("session",session,"row",info.get("own")));phase=31;probe=null;after=ticks+20;
            }
            if(Boolean.getBoolean("mineagent.deliveryGoalSmoke")&&phase==31&&!goalDraftReported&&probe!=null&&probe.get("draft").getAsString().equals("KEEP_DRAFT_"+ROLE)){goalDraftReported=true;control("draft-ready");}
            if(phase==31&&(!Boolean.getBoolean("mineagent.deliveryGoalSmoke")||info.get("originFinished").getAsBoolean())&&!busy&&probe!=null&&probe.get("draft").getAsString().equals("KEEP_DRAFT_"+ROLE)&&(!wakeMode()||info.get("wakeReady").getAsBoolean())){write("draft-observed.json",probe);if(feedbackMode()){frame("document.querySelector('#question').value='QUESTION_"+ROLE+"';document.querySelector('#feedback-submit').scrollIntoView({block:'nearest'});document.querySelector('#feedback-submit').click();");phase=40;probe=null;}else{control("ready");phase=4;}after=ticks+30;}
            if(feedbackMode()&&Set.of(40,41).contains(phase)&&probe!=null&&!probe.get("feedbackError").getAsString().isEmpty())throw new IllegalStateException("FEEDBACK_PAGE_ERROR_"+probe.get("feedbackError").getAsString());
            if(phase==40&&!busy&&probe!=null&&probe.has("feedback")&&!probe.get("feedback").isJsonNull()&&!probe.get("feedbackPending").getAsBoolean()){
                write("feedback-submitted.json",probe);feedbackOp=UUID.fromString(probe.get("feedbackOperation").getAsString());feedbackId=UUID.fromString(probe.getAsJsonObject("feedback").get("feedbackId").getAsString());feedbackBody=Map.of("event","help","payload",JSON.toJson(Map.of("question","QUESTION_"+ROLE)),"documentId",document);FeedbackRestartSmokeClient.prepare(session,feedbackOp,feedbackId,feedbackBody);
                frame("document.querySelector('#feedback-submit').click();");phase=41;probe=null;after=ticks+30;
            }
            if(phase==41&&!busy&&probe!=null&&probe.get("feedbackAttempts").getAsInt()>=2&&!probe.get("feedbackPending").getAsBoolean()&&info.has("feedback")&&(dataMode()?Set.of("COMPLETED","REJECTED").contains(info.getAsJsonObject("feedback").get("state").getAsString()):info.getAsJsonObject("feedback").get("state").getAsString().equals(wakeMode()?"PROCESSING":"RECORDED"))&&(!dataMode()||probe.has("feedbackShared")&&!probe.get("feedbackShared").isJsonNull())&&info.has("otherFeedbackId")){write("feedback-duplicate.json",probe);if(dataMode()){var shared=probe.getAsJsonObject("feedbackShared").getAsJsonObject("values");if(shared.get("count").getAsInt()!=1||shared.has("ownerSecret")||shared.has("entry")&&!shared.get("entry").getAsString().equals("QUESTION_"+ROLE))throw new IllegalStateException("FEEDBACK_DATA_SCOPE_OR_LIMIT");write("feedback-shared-view.json",probe);}phase=42;}
            if(phase==42)feedbackNegative();
            if(phase==43&&ticks>=after&&!busy&&PackageContentClient.session(view)==null){busy=true;UiClientSessions.contentRequest("command",session,"feedback.submit",feedbackBody,feedbackOp).whenComplete((r,error)->mc.execute(()->{try{if(error!=null||Set.of(Code.APPLIED,Code.ACCEPTED,Code.OBSERVED,Code.IN_PROGRESS).contains(r.code()))throw new IllegalStateException("FEEDBACK_HIDDEN_REPLAY");write("feedback-hidden-replay.json",r);main("document.querySelector('#minimized button')?.click();");busy=false;phase=44;after=ticks+40;}catch(Exception e){fail(e);}}));}
            if(phase==44&&ticks>=after&&!busy&&PackageContentClient.session(view)!=null){write("feedback-restored-session.json",PackageContentClient.session(view));control("ready");phase=4;after=ticks+30;}
            if(phase==4&&cancelWake()&&info.get("phase").getAsInt()>=71){phase=5;after=ticks+20;}
            if(phase==4&&!busy&&ticks>=after&&info.get("phase").getAsInt()>=2&&probe!=null){var state=probe.getAsJsonObject("state");String expected=ROLE.equals("A")?"ONLY_A_UPDATED":"INITIAL_DELIVERY";if(state.getAsJsonObject("data").get("text").getAsString().equals(expected)){if(!probe.get("draft").getAsString().equals("KEEP_DRAFT_"+ROLE)||!PackageContentClient.session(view).sessionId().equals(session.sessionId()))throw new IllegalStateException("DELIVERY_DATA_UPDATE_LOST_DRAFT_OR_SESSION");write("updated.json",Map.of("probe",probe,"session",session,"document",document==null?"":document));snapshot(5);}}
            if(phase==5&&wakeMode()&&ROLE.equals("B")&&!replySeen&&!busy&&probe!=null&&probe.has("feedbackReply")&&probe.get("feedbackReply").getAsString().contains("QUESTION_B")){
                if(probe.get("feedbackReply").getAsString().contains("QUESTION_A"))throw new IllegalStateException("FEEDBACK_REPLY_CROSSED_AUTHORS");write("feedback-reply-seen.json",probe);replySeen=true;busy=true;frame("document.querySelector('#proof').textContent='';document.querySelector('#feedback-answer').scrollIntoView({block:'nearest'});");
                PackagePageAgent.captureManagedView(view).whenComplete((shot,error)->mc.execute(()->{try{if(error!=null)throw new IllegalStateException(error);Files.write(root().resolve("feedback-reply-private.png"),shot.png());net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("feedback-reply-game.png"));mc.execute(()->{busy=false;control("reply-seen");});}catch(Exception e){mc.execute(()->fail(e));}});}catch(Exception e){fail(e);}}));
            }
            if(phase==5&&WebGuiHostAdapter.INSTANCE.packageUrl(view)==null&&!busy){busy=true;UiClientSessions.contentRequest("command",session,"delivery.read",Map.of(),UUID.randomUUID()).whenComplete((r,error)->mc.execute(()->{if(error!=null)fail(new IllegalStateException(error));else{closedRead=r;busy=false;phase=6;after=ticks+80;}}));}
            if(phase==6&&ticks>=after&&info.get("phase").getAsInt()>=5){if(Set.of(Code.APPLIED,Code.OBSERVED,Code.ACCEPTED,Code.IN_PROGRESS).contains(closedRead.code())||host.packageUrl(view)!=null||!ContentDeliveryClient.views().isEmpty())throw new IllegalStateException("DELIVERY_REOPENED_OR_OLD_READ_ALLOWED");write("closed.json",Map.of("oldRead",closedRead,"row",info.get("own"),"noReopen",true));if(feedbackMode()){phase=60;historyAfterClose();}else{control("done");phase=90;after=ticks+30;}}
            if(phase==61&&(wakeMode()||dataMode())&&ticks>=after&&!busy){main("document.querySelector('[data-view-id=\"runtime-feedback-"+info.getAsJsonObject("own").get("deliveryId").getAsString()+"\"] section button')?.click();");phase=62;after=ticks+40;}
            if((phase==61&&!wakeMode()&&!dataMode()||phase==62)&&ticks>=after&&!busy){busy=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("feedback-history-game.png"));mc.execute(()->{busy=false;control("done");phase=90;after=ticks+30;});}catch(Exception e){mc.execute(()->fail(e));}});}
            if(phase==90&&ticks>=after){write("result.json",Map.of("status","REAL_CLIENT_DELIVERY_VERIFIED","role",ROLE,"providerCalls",0,"systemInputInjected",false));ended=true;host.close();mc.stop();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_DELIVERY_CLIENT_OK role={}",ROLE);}
        }catch(Exception e){fail(e);}
    }
    private static void fail(Exception e){if(ended)return;ended=true;try{write("failure.json",Map.of("phase",phase,"error",e.toString(),"probe",probe==null?new JsonObject():probe));}catch(Exception ignored){}WebGuiHostAdapter.INSTANCE.close();Minecraft.getInstance().stop();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.error("DELIVERY_CLIENT_FAILED role={} phase={} error={}",ROLE,phase,e.toString());}
}
