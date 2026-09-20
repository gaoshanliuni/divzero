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

/** Actual independent Minecraft/CEF processes and play-protocol traffic. No server static fields are read here. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class UiMultiplayerSmokeClient {
    private static final Gson JSON=new Gson();private static final String ROLE=System.getProperty("mineagent.uiMultiplayerRole","");
    private static JsonObject info,probe;private static int ticks,phase,after;private static boolean opened,busy,ended,foreignSent,oldSent,duplicateSent;
    private static Session first,second;private static Request submitted;private static UUID replayId;private static Receipt staleReceipt,duplicateReceipt;
    public static boolean enabled(){return Set.of("A","B").contains(ROLE);}
    public static boolean accept(UiPayloads.Event p){
        if(!enabled())return false;
        if(p.json().contains("PRIVATE_"+(ROLE.equals("A")?"B":"A")+"_"))throw new IllegalStateException("FOREIGN_PRIVATE_UI_PACKET");
        if(p.channel().equals("uiMultiFixture")){info=JsonParser.parseString(p.json()).getAsJsonObject();return true;}
        if(replayId!=null&&p.requestId().equals(replayId)&&p.channel().equals("receipt")){staleReceipt=JSON.fromJson(p.json(),Receipt.class);return true;}
        return false;
    }
    public static void sent(String channel,Request request){
        if(enabled()&&submitted==null&&info!=null&&info.has("decisionId")&&channel.equals("command")&&request.action().equals("decision.submit")&&info.get("decisionId").getAsString().equals(request.arguments().get("decisionId")))submitted=request;
    }
    public static void probe(JsonObject value){if(enabled()){probe=value;try{write("last-probe.json",value);}catch(Exception failure){fail(failure);}}}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("ui-multiplayer-evidence").resolve(info==null?"startup":info.get("run").getAsString());}
    private static void write(String name,Object value)throws Exception{Files.createDirectories(root());Files.writeString(root().resolve(name),JSON.toJson(value));}
    private static void control(String action){ClientPacketDistributor.sendToServer(new UiPayloads.Command(UUID.randomUUID(),"uiMultiFixture",JSON.toJson(Map.of("action",action))));}
    private static void main(String code){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+code+"})();",h.browser().getURL(),0);}
    private static void connect(){var mc=Minecraft.getInstance();String address="127.0.0.1:25579";ConnectScreen.startConnecting(new TitleScreen(),mc,ServerAddress.parseString(address),new ServerData("UI multiplayer fixture",address,ServerData.Type.OTHER),false,null);opened=false;}
    private static void disconnect(int next){WebGuiHostAdapter.INSTANCE.close();Minecraft.getInstance().disconnectFromWorld(net.minecraft.network.chat.Component.literal("UI fixture reconnect"));opened=false;probe=null;phase=next;after=ticks+60;}
    @SubscribeEvent public static void tick(ClientTickEvent.Post e)throws Exception{
        if(!enabled()||ended)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;
        try{
            if(mc.player==null&&ticks>300&&phase!=2&&phase!=5&&phase!=3&&phase!=6&&mc.screen!=null&&mc.screen.getClass().getSimpleName().contains("Disconnected"))throw new IllegalStateException("FIXTURE_CONNECTION_REJECTED");
            if(mc.player!=null&&!opened){opened=true;host.open();}
            if(mc.player!=null&&ticks%20==0)control("info");
            if((phase==2||phase==5)&&mc.getConnection()==null&&ticks>=after){connect();phase=phase==2?3:6;}
            boolean ready=host.ready()&&UiClientSessions.current()!=null&&info!=null&&info.has("ready")&&info.get("ready").getAsBoolean();
            if(ready&&ticks%10==0){
                String script="""
                    document.querySelector('#open-decisions').click();
                    let card=document.querySelector('[data-decision-id="'+ID+'"]');
                    if(!card){const history=document.querySelector('#decision-entry');if(history&&[...history.options].some(o=>o.value===ID)){history.value=ID;history.dispatchEvent(new Event('change',{bubbles:true}));card=document.querySelector('[data-decision-id="'+ID+'"]');}}if(!card)return;
                    if(PREPARE&&!window.__dualPrepared){const input=card.querySelector('textarea'),option=card.querySelector('[data-option-id=b]');if(input&&option){window.__dualPrepared=true;option.click();input.value=TEXT;input.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:TEXT}));}}
                    window.mineagentQuery({request:JSON.stringify({channel:'uiMultiProbe',decisionId:card.dataset.decisionId,revision:card.dataset.revision,
                    text:document.body.innerText,draft:card.querySelector('textarea')?.value??'',selected:!!card.querySelector('[data-option-id=b]')?.checked,status:card.querySelector('[role=status]')?.textContent??''}),persistent:false,onSuccess(){},onFailure(){}});
                    """.replace("ID",JSON.toJson(info.get("decisionId").getAsString())).replace("PREPARE",Boolean.toString(phase<=1)).replace("TEXT",JSON.toJson("专属答复 "+ROLE+" · 保留数据"));
                main(script);
            }
            if(probe!=null&&probe.get("text").getAsString().contains("PRIVATE_"+(ROLE.equals("A")?"B":"A")+"_"))throw new IllegalStateException("FOREIGN_PRIVATE_DOM");
            if(ready&&phase==0&&!foreignSent){
                first=UiClientSessions.current();foreignSent=true;
                UiClientSessions.command("decision.submit",Map.of("decisionId",info.get("foreignDecisionId").getAsString(),"expectedRevision","1","submissionId",UUID.randomUUID().toString(),"selectedCount","1","selected.0","b","customText","forbidden cross-viewer attempt"),UUID.randomUUID()).whenComplete((r,error)->{
                    try{if(error!=null||r.code()!=Code.PERMISSION_DENIED)throw new IllegalStateException("FOREIGN_DECISION_NOT_REJECTED");write("foreign-rejected.json",r);phase=1;after=ticks+40;}catch(Exception failure){fail(failure);}
                });
            }
            if(ready&&phase==1&&ticks>=after&&probe!=null&&probe.get("selected").getAsBoolean()&&probe.get("draft").getAsString().equals("专属答复 "+ROLE+" · 保留数据")&&!busy){
                if(ROLE.equals("A")){write("pending-before-disconnect.json",Map.of("session",first,"probe",probe));disconnect(2);}
                else if(!info.get("aOnline").getAsBoolean()||info.get("aJoins").getAsInt()>=2){submit();phase=7;}
            }
            if(ready&&phase==3&&probe!=null){
                second=UiClientSessions.current();if(second.sessionId().equals(first.sessionId()))throw new IllegalStateException("PENDING_RECONNECT_REUSED_SESSION");
                if(probe.get("draft").getAsString().equals("专属答复 A · 保留数据")&&probe.get("selected").getAsBoolean()){
                    write("pending-restored.json",Map.of("session",second,"probe",probe));submit();phase=4;
                }
            }
            if(ready&&phase==4&&info.get("builds").getAsInt()==1&&probe!=null&&probe.get("status").getAsString().contains("服务器已确认")){
                if(submitted==null)throw new IllegalStateException("OWN_SUBMISSION_NOT_CAPTURED");write("accepted-before-reconnect.json",Map.of("session",second,"probe",probe,"request",submitted));disconnect(5);
            }
            if(ready&&phase==6&&probe!=null&&probe.get("status").getAsString().contains("服务器已确认")){
                var current=UiClientSessions.current();if(current.sessionId().equals(first.sessionId())||current.sessionId().equals(second.sessionId()))throw new IllegalStateException("RESOLVED_RECONNECT_REUSED_SESSION");
                if(!oldSent){oldSent=true;replayId=submitted.operationId();ClientPacketDistributor.sendToServer(new UiPayloads.Command(submitted.operationId(),"command",JSON.toJson(submitted)));}
                if(staleReceipt!=null&&!duplicateSent){
                    if(staleReceipt.code()!=Code.VIEW_NOT_RENDERED)throw new IllegalStateException("OLD_SESSION_REPLAY_ACCEPTED");duplicateSent=true;
                    UiClientSessions.command("decision.submit",submitted.arguments(),UUID.randomUUID()).whenComplete((r,error)->{if(error!=null)fail(new IllegalStateException(error));else duplicateReceipt=r;});
                }
                if(duplicateReceipt!=null){if(duplicateReceipt.code()!=Code.APPLIED||info.get("builds").getAsInt()!=1)throw new IllegalStateException("DUPLICATE_ANSWER_FAILED");write("reconnected-replay.json",Map.of("current",current,"oldSessionReceipt",staleReceipt,"freshSessionDuplicate",duplicateReceipt,"probe",probe));phase=90;}
            }
            if(ready&&ROLE.equals("B")&&phase==7&&info.get("aDone").getAsBoolean()&&!info.get("aOnline").getAsBoolean()){
                if(!UiClientSessions.current().sessionId().equals(first.sessionId())||info.get("builds").getAsInt()!=1||probe==null||!probe.get("status").getAsString().contains("服务器已确认"))throw new IllegalStateException("OTHER_VIEWER_RECONNECT_DISRUPTED_B");
                write("survived-other-client.json",Map.of("session",UiClientSessions.current(),"probe",probe));phase=90;
            }
            if(phase==90&&!busy){busy=true;write("visible-agents.json",Map.of("expected",info.get("agentIds"),"actual",mc.level.players().stream().map(p->p.getUUID()).toList()));
                for(var id:info.getAsJsonArray("agentIds"))if(mc.level.players().stream().noneMatch(p->p.getUUID().toString().equals(id.getAsString())))throw new IllegalStateException("REAL_AI_REPLICA_NOT_VISIBLE");
                net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("gui-"+ROLE+".png"));host.close();phase=91;after=ticks+20;busy=false;}catch(Exception failure){fail(failure);}});
            }
            if(phase==91&&ticks>=after&&!busy){busy=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("world-"+ROLE+".png"));phase=92;after=ticks+20;busy=false;}catch(Exception failure){fail(failure);}});}
            if(phase==92&&ticks>=after){write("result.json",Map.of("role",ROLE,"status","REAL_CLIENT_UI_VERIFIED","providerCalls",0,"driver","NATIVE_GAME_AND_TRUSTED_DOM_NOT_OS_IME"));control("done");phase=93;after=ticks+20;}
            if(phase==93&&ticks>=after){ended=true;host.close();mc.stop();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_UI_MULTIPLAYER_CLIENT_OK role={}",ROLE);}
            if(ticks>18000)throw new IllegalStateException("UI_MULTIPLAYER_CLIENT_TIMEOUT phase="+phase);
        }catch(Exception failure){fail(failure);}
    }
    private static void submit(){main("const c=document.querySelector('[data-decision-id=\""+info.get("decisionId").getAsString()+"\"]');if(!window.__dualSubmitted){window.__dualSubmitted=true;c.querySelector('[data-ai-id=decision-submit]').click();}");}
    private static void fail(Exception error){ended=true;try{write("failure.json",Map.of("phase",phase,"error",error.toString(),"probe",probe==null?new JsonObject():probe));}catch(Exception ignored){}WebGuiHostAdapter.INSTANCE.close();Minecraft.getInstance().stop();throw new IllegalStateException("UI_MULTIPLAYER_CLIENT_FAILED",error);}
}
