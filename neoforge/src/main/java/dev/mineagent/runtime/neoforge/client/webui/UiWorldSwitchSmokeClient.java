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

/** One real client stays alive while the same endpoint changes to a different world/server JVM. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class UiWorldSwitchSmokeClient {
    private static final Gson JSON=new Gson();private static JsonObject info,probe;private static Session oldSession;private static Request oldRequest;
    private static Receipt oldReceipt,oldContextReceipt;private static int phase,ticks,after;private static boolean opened,ended,busy,replaySent,contextSent;
    public static boolean enabled(){return Boolean.getBoolean("mineagent.uiWorldSwitchClient");}
    public static boolean accept(UiPayloads.Event p){
        if(!enabled())return false;
        if(p.channel().equals("uiWorldFixture")){info=JsonParser.parseString(p.json()).getAsJsonObject();return true;}
        if(oldRequest!=null&&p.requestId().equals(oldRequest.operationId())&&p.channel().equals("receipt")){oldReceipt=JSON.fromJson(p.json(),Receipt.class);return true;}
        return false;
    }
    public static void probe(JsonObject p){if(enabled())probe=p;}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("ui-world-switch-evidence");}
    private static void write(String name,Object data)throws Exception{Files.createDirectories(root());Files.writeString(root().resolve(name),JSON.toJson(data));}
    private static void command(String action){ClientPacketDistributor.sendToServer(new UiPayloads.Command(UUID.randomUUID(),"uiWorldFixture",JSON.toJson(Map.of("action",action))));}
    private static void main(String code){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+code+"})();",h.browser().getURL(),0);}
    private static void connect(){var mc=Minecraft.getInstance();String address="127.0.0.1:25589";ConnectScreen.startConnecting(new TitleScreen(),mc,ServerAddress.parseString(address),new ServerData("World switch fixture",address,ServerData.Type.OTHER),false,null);opened=false;probe=null;info=null;}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!enabled()||ended)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;
        try{
            if(mc.player!=null&&!opened){opened=true;host.open();}
            if(mc.player!=null&&ticks%20==0)command("info");
            if(phase==1&&mc.getConnection()==null){phase=2;opened=false;probe=null;}
            if(phase==2&&Files.exists(Path.of(System.getProperty("mineagent.uiWorldSwitchSignal")))){connect();phase=3;after=ticks+40;}
            boolean ready=host.ready()&&UiClientSessions.current()!=null&&info!=null&&info.has("ready")&&info.get("ready").getAsBoolean();
            if(ready&&ticks%10==0){
                main("""
                  document.querySelector('#open-decisions').click();
                  if(!document.querySelector('#chat-draft'))document.querySelector('#open-chat').click();
                  const c=document.querySelector('[data-decision-id="'+ID+'"]');if(!c)return;
                  if(FILL&&!window.__worldDraft){window.__worldDraft=true;c.querySelector('[data-option-id=b]').click();const t=c.querySelector('textarea');t.value='这次木质 · OLD_WORLD_DRAFT';t.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:t.value}));const chat=document.querySelector('#chat-draft');chat.value='OLD_WORLD_CHAT';chat.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:chat.value}));}
                  window.mineagentQuery({request:JSON.stringify({channel:'uiWorldProbe',text:document.body.innerText,draft:c.querySelector('textarea')?.value??'',selected:!!c.querySelector('[data-option-id=b]')?.checked,chatDraft:document.querySelector('#chat-draft').value,status:document.querySelector('#chat-decision-result')?.textContent??''}),persistent:false,onSuccess(){},onFailure(){}});
                  """.replace("ID",JSON.toJson(info.get("firstDecision").getAsString())).replace("FILL",Boolean.toString(phase==0)));
            }
            if(ready&&phase==0&&probe!=null&&probe.get("draft").getAsString().equals("这次木质 · OLD_WORLD_DRAFT")){
                if(after==0){after=ticks+40;return;}if(ticks<after)return;
                oldSession=UiClientSessions.current();oldRequest=new Request(UUID.randomUUID(),oldSession.sessionId(),oldSession.pageGeneration(),oldSession.controlEpoch(),oldSession.binding().taskRevision(),"decision.submit",Map.of("decisionId",info.get("firstDecision").getAsString(),"expectedRevision","1","submissionId",UUID.randomUUID().toString(),"selectedCount","1","selected.0","b","customText","OLD_WORLD_DRAFT"));
                write("world-one.json",Map.of("session",oldSession,"info",info,"probe",probe,"oldRequest",oldRequest));command("switch");phase=1;
            }
            if(ready&&phase==3&&ticks>=after&&info.get("stage").getAsInt()==2&&probe!=null){
                var fresh=UiClientSessions.current();if(fresh.binding().worldId().equals(oldSession.binding().worldId())||fresh.serverInstanceId().equals(oldSession.serverInstanceId()))throw new IllegalStateException("WORLD_CONTEXT_NOT_REPLACED");
                if(probe.get("text").getAsString().contains("WORLD_ONE_PRIVATE_ONLY")||!probe.get("draft").getAsString().isEmpty()||!probe.get("chatDraft").getAsString().isEmpty()||probe.get("selected").getAsBoolean())throw new IllegalStateException("OLD_WORLD_STATE_LEAKED");
                write("world-two-clean.json",Map.of("session",fresh,"info",info,"probe",probe));phase=4;
            }
            if(ready&&phase==4){
                if(!replaySent){replaySent=true;ClientPacketDistributor.sendToServer(new UiPayloads.Command(oldRequest.operationId(),"command",JSON.toJson(oldRequest)));}
                if(oldReceipt!=null&&!contextSent){
                    if(oldReceipt.code()!=Code.VIEW_NOT_RENDERED)throw new IllegalStateException("OLD_WORLD_SESSION_ACCEPTED");contextSent=true;
                    UiClientSessions.command("chat.send",Map.of("agentId",info.get("agentId").getAsString(),"decisionId",oldRequest.arguments().get("decisionId"),"decisionRevision","1","text","第二个"),UUID.randomUUID()).whenComplete((r,error)->{if(error!=null)fail(new IllegalStateException(error));else oldContextReceipt=r;});
                }
                if(oldContextReceipt!=null){
                    if(Set.of(Code.ACCEPTED,Code.APPLIED,Code.OBSERVED).contains(oldContextReceipt.code()))throw new IllegalStateException("OLD_DECISION_CONTEXT_ACCEPTED");
                    write("old-world-rejected.json",Map.of("oldSession",oldReceipt,"oldQuestionInNewSession",oldContextReceipt));phase=5;
                }
            }
            if(ready&&phase==5){
                main("""
                    const a=document.querySelector('#chat-agent');if(![...a.options].some(o=>o.value===AGENT))return;a.value=AGENT;a.dispatchEvent(new Event('change',{bubbles:true}));
                    const d=document.querySelector('#chat-decision');if(![...d.options].some(o=>o.value===DECISION))return;
                    if(!window.__worldAnswer){window.__worldAnswer=true;d.value=DECISION;d.dispatchEvent(new Event('change',{bubbles:true}));const t=document.querySelector('#chat-draft');t.value='第二个';t.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:t.value}));[...t.parentElement.querySelectorAll('button')].find(b=>b.textContent==='发送私密消息').click();}
                    """.replace("AGENT",JSON.toJson(info.get("agentId").getAsString())).replace("DECISION",JSON.toJson(info.get("secondDecision").getAsString())));
                if(info.get("applied").getAsInt()==1){write("target-selected.json",Map.of("probe",probe,"info",info));phase=6;after=ticks+30;}
            }
            if(phase==6&&ticks>=after&&!busy){busy=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("world-two-ui.png"));phase=7;busy=false;}catch(Exception failure){fail(failure);}});}
            if(phase==7){write("result.json",Map.of("status","SAME_ENDPOINT_WORLD_AND_TASK_ISOLATION_VERIFIED","providerCalls",0));command("done");phase=8;after=ticks+20;}
            if(phase==8&&ticks>=after){ended=true;host.close();mc.stop();}
            if(ticks>24000)throw new IllegalStateException("WORLD_SWITCH_CLIENT_TIMEOUT phase="+phase);
        }catch(Exception failure){fail(failure);}
    }
    private static void fail(Exception e){ended=true;try{write("failure.json",Map.of("phase",phase,"error",e.toString(),"probe",probe==null?new JsonObject():probe));}catch(Exception ignored){}WebGuiHostAdapter.INSTANCE.close();Minecraft.getInstance().stop();throw new IllegalStateException("UI_WORLD_SWITCH_FAILED",e);}
}
