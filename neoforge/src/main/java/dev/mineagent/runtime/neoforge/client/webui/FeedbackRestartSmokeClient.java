package dev.mineagent.runtime.neoforge.client.webui;

import com.google.gson.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.nio.file.*;
import java.util.*;

/** Client-side restart evidence, real new shell Session and rejected old wire Session. No OS input. */
final class FeedbackRestartSmokeClient {
    private static final Gson JSON=new Gson();private static boolean connected,ended,busy,saved;private static int phase,ticks,after,reacceptPhase;private static String reopened;private static Session fresh;private static JsonObject originalWire;
    static String stage(){return System.getProperty("mineagent.feedbackRestartStage","");}
    static boolean enabled(){return !stage().isEmpty();}
    private static boolean reaccept(){return Boolean.getBoolean("mineagent.feedbackReacceptSmoke");}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("feedback-restart-evidence");}
    private static void write(String name,Object value)throws Exception{Files.createDirectories(root());Files.writeString(root().resolve(name),JSON.toJson(value));}
    private static void main(String js){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+js+"})();",h.browser().getURL(),0);}
    private static void done(){ClientPacketDistributor.sendToServer(new UiPayloads.Command(UUID.randomUUID(),"deliveryFixture","{\"action\":\"done\"}"));}
    static void captureWire(Session session,UUID operation,Map<String,String> arguments)throws Exception{
        if(!stage().equals("prepare")||saved)return;
        write("old-request.json",Map.of("session",session,"operation",operation,"arguments",arguments));saved=true;
    }
    private static void stop(String result)throws Exception{if(ended)return;ended=true;write(stage()+"-result.json",Map.of("status",result,"systemInputInjected",false));Minecraft.getInstance().stop();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_DELIVERY_CLIENT_OK role={} feedbackRestart={}",System.getProperty("mineagent.deliverySmokeRole"),stage());}
    static boolean prepare(Session session,UUID op,UUID id,Map<String,String> body)throws Exception{
        if(!stage().equals("prepare"))return false;var mc=Minecraft.getInstance();if(ended)return true;
        if(mc.player!=null)connected=true;
        if(!saved&&session!=null&&op!=null&&id!=null&&body!=null){write("old-request.json",Map.of("session",session,"operation",op,"feedbackId",id,"arguments",body));saved=true;}
        if(connected&&mc.player==null){if(!saved)throw new IllegalStateException("FEEDBACK_RESTART_CLIENT_REQUEST_NOT_SAVED");stop("REAL_CLIENT_DISCONNECTED_AT_SERVER_STOP");return true;}return false;
    }
    static boolean resume(boolean trusted,JsonObject info)throws Exception{
        if(!stage().equals("resume"))return false;if(ended)return true;ticks++;if(ticks>12000)throw new IllegalStateException("FEEDBACK_RESTART_CLIENT_TIMEOUT");var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;
        if(phase==0&&trusted&&info!=null&&info.get("ready").getAsBoolean()){if(!ContentDeliveryClient.views().isEmpty())throw new IllegalStateException("FEEDBACK_RESTART_REOPENED_CONTENT");host.open();phase=1;}
        if(phase==1&&host.ready()&&UiClientSessions.current()!=null){main("document.querySelector('#open-deliveries').click();");phase=2;after=ticks+40;}
        if(phase==2&&ticks>=after&&!busy){busy=true;var saved=JsonParser.parseString(Files.readString(root().resolve("old-request.json"))).getAsJsonObject();var old=JSON.fromJson(saved.get("session"),Session.class);var args=new LinkedHashMap<String,String>();saved.getAsJsonObject("arguments").entrySet().forEach(e->args.put(e.getKey(),e.getValue().getAsString()));
            UiClientSessions.contentRequest("command",old,"feedback.submit",args,UUID.fromString(saved.get("operation").getAsString())).whenComplete((r,error)->mc.execute(()->{try{if(error!=null||!Set.of(Code.VIEW_NOT_RENDERED,Code.STALE_VIEW,Code.EXPIRED,Code.PERMISSION_DENIED).contains(r.code()))throw new IllegalStateException("FEEDBACK_RESTART_OLD_SESSION_ACCEPTED");write("old-session-rejected.json",r);busy=false;phase=3;}catch(Exception e){fail(e);}}));
        }
        if(phase==3&&!busy){busy=true;String id=info.getAsJsonObject("feedback").get("id").getAsString();UiClientSessions.command("feedback.read",Map.of("feedbackId",id),UUID.randomUUID()).whenComplete((r,error)->mc.execute(()->{try{if(error!=null||r.code()!=Code.OBSERVED)throw new IllegalStateException("FEEDBACK_RESTART_HISTORY_READ");var state=JsonParser.parseString(r.values().get("feedback")).getAsJsonObject();if(!state.get("state").getAsString().equals(info.getAsJsonObject("feedback").get("state").getAsString())||!r.values().get("payload").contains("QUESTION_"+System.getProperty("mineagent.deliverySmokeRole")))throw new IllegalStateException("FEEDBACK_RESTART_HISTORY_CHANGED");write("history-read.json",r);busy=false;phase=4;}catch(Exception e){fail(e);}}));}
        if(phase==4&&!busy){busy=true;UiClientSessions.command("feedback.read",Map.of("feedbackId",info.get("otherFeedbackId").getAsString()),UUID.randomUUID()).whenComplete((r,error)->mc.execute(()->{try{if(error!=null||Set.of(Code.OBSERVED,Code.APPLIED,Code.ACCEPTED).contains(r.code()))throw new IllegalStateException("FEEDBACK_RESTART_PEER_HISTORY");write("peer-history-rejected.json",r);String delivery=info.getAsJsonObject("own").get("deliveryId").getAsString();main("document.querySelector('[data-delivery-id=\""+delivery+"\"] [data-action=feedback-history]')?.click();");busy=false;phase=5;after=ticks+60;}catch(Exception e){fail(e);}}));}
        if(phase==5&&ticks>=after&&!busy){String delivery=info.getAsJsonObject("own").get("deliveryId").getAsString();main("document.querySelector('[data-view-id=\"runtime-feedback-"+delivery+"\"] section button')?.click();");phase=6;after=ticks+40;}
        if(phase==6&&ticks>=after&&!busy){busy=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("resume-history-game.png"));mc.execute(()->{busy=false;if(reaccept())phase=20;else{done();phase=7;after=ticks+20;}});}catch(Exception e){mc.execute(()->fail(e));}});}
        if(phase==20)reacceptTick(info);
        if(phase==7&&(ticks>=after||mc.player==null))stop("REAL_CLIENT_NEW_SESSION_PRIVATE_HISTORY_VERIFIED");return true;
    }
    private static void frame(String script){var h=WebGuiHostAdapter.INSTANCE;String url=h.packageUrl(reopened);for(long id:PackagePageAgent.frameIds(h.browser().getFrameIdentifiers())){var f=h.browser().getFrame(id);if(f!=null&&!f.isMain()&&Objects.equals(url,f.getURL())){f.executeJavaScript("(()=>{"+script+"})();",url,0);return;}}throw new IllegalStateException("REACCEPT_FRAME_MISSING");}
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    private static Map<String,String> previousArguments(){var args=new LinkedHashMap<String,String>();originalWire.getAsJsonObject("arguments").entrySet().stream().filter(e->!e.getKey().equals("documentId")).forEach(e->args.put(e.getKey(),e.getValue().getAsString()));return args;}
    private static void reacceptTick(JsonObject info)throws Exception{
        var mc=Minecraft.getInstance();String role=System.getProperty("mineagent.deliverySmokeRole");String delivery=info.getAsJsonObject("own").get("deliveryId").getAsString();String feedback=info.getAsJsonObject("feedback").get("id").getAsString();
        if(reacceptPhase==0){originalWire=JsonParser.parseString(Files.readString(root().resolve("old-request.json"))).getAsJsonObject();require(ContentDeliveryClient.views().isEmpty(),"REACCEPT_OPENED_AUTOMATICALLY");
            main("document.querySelector('[data-view-id=\"runtime-feedback-"+delivery+"\"] button[aria-label=\"关闭\"]')?.click();document.querySelector('#open-deliveries').click();");reacceptPhase=1;after=ticks+30;
        }
        if(reacceptPhase==1&&ticks>=after&&ticks%10==0){main("const b=document.querySelector('[data-delivery-id=\""+delivery+"\"] [data-action=delivery-open]');if(b&&!b.disabled&&!window.__reacceptClicked){window.__reacceptClicked=true;b.click();}");
            if(ContentDeliveryClient.views().size()==1){reopened=ContentDeliveryClient.views().getFirst();fresh=PackageContentClient.session(reopened);if(fresh!=null){var old=JSON.fromJson(originalWire.get("session"),Session.class);require(!old.sessionId().equals(fresh.sessionId())&&!old.serverInstanceId().equals(fresh.serverInstanceId())&&!old.binding().viewId().equals(fresh.binding().viewId()),"REACCEPT_REUSED_OLD_CONTEXT");write("fresh-session.json",Map.of("old",old,"fresh",fresh));reacceptPhase=2;}}
        }
        if(reacceptPhase==2){String state=DeliveryDraftClient.status(reopened);if(state.equals("FAILED"))throw new IllegalStateException("REACCEPT_DRAFT_LOAD_FAILED");if(state.equals("AVAILABLE")&&ticks%10==0)main("document.querySelector('[data-view-id=\""+reopened+"\"] [data-action=restore-delivery-draft]')?.click();");if(state.equals("RESTORED")){reacceptPhase=3;after=ticks+20;}}
        if(reacceptPhase==3&&ticks>=after&&!busy){busy=true;PackageFormDrafts.capture(reopened).whenComplete((draft,error)->mc.execute(()->{try{
            require(error==null&&FormDraftParity.matches(draft,draft),"REACCEPT_DRAFT_CAPTURE");var fields=new HashMap<String,String>();draft.getAsJsonArray("controls").forEach(c->fields.put(c.getAsJsonObject().get("locator").getAsString(),c.getAsJsonObject().get("value").getAsString()));
            require(fields.getOrDefault("draft","").equals("KEEP_DRAFT_"+role)&&fields.getOrDefault("question","").equals("QUESTION_"+role)&&!draft.toString().contains("FEEDBACK_HIDDEN_NOT_SENT"),"REACCEPT_DRAFT_PARITY");write("restored-draft.json",draft);frame("window.deliveryProbe();");reacceptPhase=4;busy=false;after=ticks+20;
        }catch(Exception e){fail(e);}}));}
        if(reacceptPhase==4&&ticks>=after&&!busy){busy=true;PackagePageAgent.inspectManagedView(reopened).whenComplete((text,error)->mc.execute(()->{try{
            if(error!=null)throw new IllegalStateException("REACCEPT_OBSERVATION",error);String visible=JsonParser.parseString(text).getAsJsonObject().get("visibleText").getAsString();int at=visible.indexOf("DELIVERY_PROOF:");require(at>=0,"REACCEPT_PROBE_MISSING");var probe=JsonParser.parseString(visible.substring(at+15)).getAsJsonObject();require(probe.get("feedbackAttempts").getAsInt()==0&&probe.get("feedback").isJsonNull(),"REACCEPT_RESTORE_SUBMITTED_BUSINESS");write("restored-no-submit.json",probe);reacceptPhase=5;busy=false;
        }catch(Exception e){fail(e);}}));}
        if(reacceptPhase==5&&!busy&&info.getAsJsonObject("own").get("receivedDataRevision").getAsLong()>0){busy=true;
            ContentDeliveryClient.submitFeedback(reopened,fresh,previousArguments(),UUID.fromString(originalWire.get("operation").getAsString())).whenComplete((r,error)->mc.execute(()->{try{require(error==null&&Set.of(Code.ACCEPTED,Code.APPLIED).contains(r.code()),"REACCEPT_EXPLICIT_DUPLICATE_DENIED");var f=JsonParser.parseString(r.values().get("feedback")).getAsJsonObject();require(f.get("feedbackId").getAsString().equals(feedback)&&f.get("state").getAsString().equals(info.getAsJsonObject("feedback").get("state").getAsString()),"REACCEPT_DUPLICATE_REEXECUTED");write("new-session-old-operation.json",r);reacceptPhase=6;busy=false;}catch(Exception e){fail(e);}}));
        }
        if(reacceptPhase==6&&!busy){busy=true;var changed=previousArguments();changed.put("payload","{\"question\":\"CHANGED_MUST_NOT_REPLAY\"}");ContentDeliveryClient.submitFeedback(reopened,fresh,changed,UUID.fromString(originalWire.get("operation").getAsString())).whenComplete((r,error)->mc.execute(()->{try{require(error==null&&!Set.of(Code.ACCEPTED,Code.APPLIED,Code.OBSERVED,Code.IN_PROGRESS).contains(r.code()),"REACCEPT_CHANGED_OPERATION_ACCEPTED");write("new-session-changed-operation-rejected.json",r);reacceptPhase=7;busy=false;}catch(Exception e){fail(e);}}));}
        if(reacceptPhase==7&&!busy){busy=true;UiClientSessions.contentRequest("command",fresh,"feedback.stateRead",Map.of("feedbackId",feedback),UUID.randomUUID()).whenComplete((r,error)->mc.execute(()->{try{require(error==null&&r.code()==Code.OBSERVED,"REACCEPT_SHARED_READ_DENIED");var state=JsonParser.parseString(r.values().get("state")).getAsJsonObject();var values=state.getAsJsonObject("values");require(values.get("count").getAsInt()==info.get("expectedCount").getAsInt()&&!values.has("ownerSecret")&&(!values.has("entry")||values.get("entry").getAsString().equals("QUESTION_"+role)),"REACCEPT_SHARED_PRIVATE_DATA");write("fresh-shared-read.json",r);reacceptPhase=8;busy=false;}catch(Exception e){fail(e);}}));}
        if(reacceptPhase==8&&!busy){busy=true;UiClientSessions.contentRequest("command",fresh,"feedback.read",Map.of("feedbackId",feedback),UUID.randomUUID()).whenComplete((r,error)->mc.execute(()->{try{
            require(error==null&&r.code()==Code.OBSERVED,"REACCEPT_OUTCOME_READ");var outcome=JsonParser.parseString(r.values().get("recovery")).getAsJsonObject();require(outcome.get("status").getAsString().equals(info.get("expectedOutcome").getAsString())&&!outcome.get("replayAllowed").getAsBoolean(),"REACCEPT_OUTCOME_MISMATCH_"+outcome.get("status").getAsString());write("fresh-outcome.json",r);
            frame("document.querySelector('#proof').textContent='';(async()=>{const f=await mineagentFeedback.inspect('"+feedback+"');const s=await mineagentFeedback.readShared('"+feedback+"');document.querySelector('#feedback-answer').textContent='恢复核验 '+f.state+' / '+f.recovery.status+' · count='+s.values.count+' · 我的记录='+(s.values.entry||'未写入');})();");reacceptPhase=9;busy=false;after=ticks+40;
        }catch(Exception e){fail(e);}}));}
        if(reacceptPhase==9&&ticks>=after&&!busy){busy=true;PackagePageAgent.captureManagedView(reopened).whenComplete((shot,error)->mc.execute(()->{try{if(error!=null)throw new IllegalStateException("REACCEPT_CAPTURE",error);Files.write(root().resolve("reaccepted-private.png"),shot.png());net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("reaccepted-game.png"));mc.execute(()->{main("document.querySelector('[data-view-id=\""+reopened+"\"] button[aria-label=\"关闭\"]')?.click();" );reacceptPhase=10;busy=false;after=ticks+60;});}catch(Exception e){mc.execute(()->fail(e));}});}catch(Exception e){fail(e);}}));}
        if(reacceptPhase==10&&ticks>=after&&info.getAsJsonObject("own").get("status").getAsString().equals("CLOSED")&&info.getAsJsonObject("own").get("closeConfirmed").getAsBoolean()){require(ContentDeliveryClient.views().isEmpty(),"REACCEPT_CLOSED_REOPENED");write("reaccept-result.json",Map.of("status","FRESH_DELIVERY_SESSION_DRAFT_AND_HISTORICAL_OUTCOME_VERIFIED","info",info,"newSession",fresh,"systemInputInjected",false));done();phase=7;after=ticks+20;}
    }
    private static void fail(Exception failure){if(ended)return;ended=true;try{write(stage()+"-failure.json",Map.of("error",failure.toString(),"phase",phase));}catch(Exception ignored){}Minecraft.getInstance().stop();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.error("DELIVERY_CLIENT_FAILED feedbackRestart={} error={}",stage(),failure.toString());}
}
