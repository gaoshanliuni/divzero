package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
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

/** Real dedicated-client protocol/UI driver; never reads server static runtime state. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class TaskAuthoritySmokeClient {
    private static final String PRE="Authority pre-dispatch",QUESTION="Authority waiting question",LATE="Authority in-flight",QUEUED="Authority queued request",POSITIVE="Authority newly authorized",DRAFT="保留我的未提交输入；不要自动重投。";
    private static final Gson JSON=new Gson();private static final Map<String,Request> starts=new HashMap<>();private static final Set<String> accepted=new HashSet<>();
    private static JsonObject info,probe;private static Request replay;private static Receipt receipt;
    private static boolean opened,ended,preDone,questionDone,replayDone,draftSent,capturing,finishSent,disconnected;private static volatile boolean captured;private static int ticks,closeAt=-1,disconnectAt=-1;
    public static boolean enabled(){return Boolean.getBoolean("mineagent.taskAuthorityClient");}
    public static void sent(String channel,Request r){if(enabled()&&channel.equals("command")&&r.action().equals("task.start"))starts.putIfAbsent(r.arguments().get("prompt"),r);}
    public static boolean accept(UiPayloads.Event p){
        if(!enabled())return false;
        if(p.channel().equals("taskAuthorityFixture")){info=JsonParser.parseString(p.json()).getAsJsonObject();return true;}
        if(p.channel().equals("receipt")){
            var value=JSON.fromJson(p.json(),Receipt.class);
            for(var entry:starts.entrySet())if(entry.getValue().operationId().equals(p.requestId())&&value.code()==Code.ACCEPTED)accepted.add(entry.getKey());
            if(replay!=null&&replay.operationId().equals(p.requestId())){receipt=value;return true;}
        }
        return false;
    }
    static void probe(JsonObject value){probe=value.deepCopy();}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("task-authority-evidence");}
    private static void write(String name,Object value)throws Exception{Files.createDirectories(root());Files.writeString(root().resolve(name),JSON.toJson(value));}
    private static void control(String action){ClientPacketDistributor.sendToServer(new UiPayloads.Command(UUID.randomUUID(),"taskAuthorityFixture",JSON.toJson(Map.of("action",action))));}
    private static void main(String script){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+script+"})();",h.browser().getURL(),0);}
    private static void start(String title){main("if(!document.querySelector('#world-task-prompt'))document.querySelector('#open-tasks').click();const agent=document.querySelector('#world-task-agent');window.__authorityStarts??={};if(!window.__authorityStarts[TITLE]&&[...agent.options].some(o=>o.value===AGENT)){window.__authorityStarts[TITLE]=true;agent.value=AGENT;const input=document.querySelector('#world-task-prompt');input.value=TITLE;input.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#world-task-start').click();}".replace("TITLE",JSON.toJson(title)).replace("AGENT",info.get("agent").toString()));}
    private static void sendReplay(Request r){replay=r;receipt=null;ClientPacketDistributor.sendToServer(new UiPayloads.Command(r.operationId(),"command",JSON.toJson(r)));}
    private static void expect(Code code){if(receipt.code()!=code)throw new IllegalStateException("AUTHORITY_RECEIPT_"+receipt.code()+"_EXPECTED_"+code);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!enabled()||ended)return;var mc=Minecraft.getInstance();var h=WebGuiHostAdapter.INSTANCE;ticks++;
        try{
            if(captured){
                if(!finishSent){control("finish");h.close();finishSent=true;closeAt=ticks;}
                if(!disconnected&&YsmRecoveryStopPolicy.mayStage(ticks,closeAt)){mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Task authority fixture finished"));disconnected=true;disconnectAt=ticks;}
                if(disconnected&&ticks-disconnectAt>=20&&YsmRecoveryStopPolicy.mayStop(true,mc.getConnection()!=null,mc.level!=null)){ended=true;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_TASK_AUTHORITY_CLIENT_OK");mc.stop();}
                return;
            }
            if(mc.player!=null&&!opened){opened=true;h.open();}
            if(mc.player!=null&&ticks%20==0)control("info");
            if(info!=null&&!info.get("failure").getAsString().isBlank())throw new IllegalStateException(info.get("failure").getAsString());
            if(h.ready()&&UiClientSessions.current()!=null&&info!=null){
                if(info.get("operator").getAsBoolean())throw new IllegalStateException("CLIENT_IS_UNEXPECTED_OP");int phase=info.get("phase").getAsInt();
                if(ticks%10==0){
                    if(phase==0)start(PRE);if(phase==2)start(QUESTION);if(phase==5)start(LATE);if(phase==6)start(QUEUED);if(phase==8&&replayDone)start(POSITIVE);
                    if(phase==3||phase==4)main("document.querySelector('#open-decisions').click();const c=document.querySelector('[data-decision-id=\"'+ID+'\"]');if(c){if(PHASE===3&&!window.__authorityDraft){window.__authorityDraft=true;c.querySelector('[data-option-id=\"a\"]').click();const text=c.querySelector('textarea');text.value=DRAFT;text.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text.value}));}window.mineagentQuery({request:JSON.stringify({channel:'taskAuthorityProbe',revision:c.dataset.revision,disabled:c.querySelector('fieldset').disabled,text:c.querySelector('textarea').value,selected:c.querySelector('[data-option-id=\"a\"]').checked,status:c.querySelector('[role=status]').textContent}),persistent:false,onSuccess(){},onFailure(){}});}".replace("ID",info.get("decision").toString()).replace("PHASE",Integer.toString(phase)).replace("DRAFT",JSON.toJson(DRAFT)));
                }
                if(phase==1&&!preDone&&accepted.contains(PRE)){
                    if(replay==null)sendReplay(starts.get(PRE));
                    if(receipt!=null){expect(Code.PERMISSION_DENIED);write("start-replay-denied.json",Map.of("request",replay,"receipt",receipt,"info",info));preDone=true;replay=null;receipt=null;control("preDone");}
                }
                if(phase==3&&!draftSent&&probe!=null&&probe.get("text").getAsString().equals(DRAFT)&&probe.get("selected").getAsBoolean()){write("draft-before.json",probe);draftSent=true;control("draftReady");}
                if(phase==4&&!questionDone&&probe!=null&&probe.get("revision").getAsInt()>1&&probe.get("disabled").getAsBoolean()){
                    if(!probe.get("text").getAsString().equals(DRAFT)||!probe.get("selected").getAsBoolean())throw new IllegalStateException("REVOKED_DRAFT_LOST");
                    if(replay==null){var session=UiClientSessions.current();var operation=UUID.randomUUID();sendReplay(new Request(operation,session.sessionId(),session.pageGeneration(),session.controlEpoch(),session.binding().taskRevision(),"decision.submit",Map.of("decisionId",info.get("decision").getAsString(),"expectedRevision","1","submissionId",operation.toString(),"selectedCount","1","selected.0","a","customText",DRAFT)));}
                    if(receipt!=null){expect(Code.PERMISSION_DENIED);write("draft-after-and-submit-denied.json",Map.of("probe",probe,"request",replay,"receipt",receipt));questionDone=true;replay=null;receipt=null;control("questionDone");}
                }
                if(phase==8&&!replayDone){
                    if(replay==null)sendReplay(starts.get(PRE));
                    if(receipt!=null){expect(Code.STATE_CONFLICT);if(!"TASK_REPLAN_REQUIRED".equals(receipt.values().get("errorCode")))throw new IllegalStateException("REGRANT_OLD_REQUEST_RESUMED");write("regranted-old-start-denied.json",Map.of("request",replay,"receipt",receipt));replayDone=true;replay=null;receipt=null;control("replayChecked");}
                }
                if(phase==9&&!capturing){write("final-client.json",Map.of("info",info,"draft",probe,"session",UiClientSessions.current(),"acceptedStarts",accepted));if(!(mc.screen instanceof WebGuiInteractionScreen))throw new IllegalStateException("AUTHORITY_GUI_NOT_VISIBLE");
                    capturing=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("final.png"));captured=true;}catch(Exception e){throw new IllegalStateException(e);}});
                }
            }
            if(ticks>9500)throw new IllegalStateException("AUTHORITY_CLIENT_TIMEOUT");
        }catch(Exception e){write("failure.json",Map.of("error",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage(),"info",info==null?new JsonObject():info,"probe",probe==null?new JsonObject():probe));ended=true;if(mc.getConnection()!=null)control("finish");h.close();mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Task authority fixture failed"));mc.stop();}
    }
}
