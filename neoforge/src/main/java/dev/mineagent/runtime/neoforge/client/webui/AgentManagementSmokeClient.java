package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.neoforge.ui.AgentManagementSmokeServer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;

/** The first mutations are actual DOM form handlers. Explicit wire repeats only test idempotence/authority. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class AgentManagementSmokeClient {
    private static final Gson JSON=new Gson();private static Request createRequest,replanRequest;private static JsonObject probe;
    private static int phase,ticks,resetAt;private static boolean opened,busy,finished;private static UUID previousSession;
    public static void sent(String channel,Request request){if(!AgentManagementSmokeServer.enabled())return;if(request.action().equals("task.control")&&"pause".equals(request.arguments().get("control")))AgentManagementSmokeServer.clientSeen.add("pause-sent");if(request.action().equals("agent.manage")&&"create".equals(request.arguments().get("kind"))&&createRequest==null)createRequest=request;if(request.action().equals("task.replan")&&replanRequest==null)replanRequest=request;}
    public static void accept(JsonObject value){if(AgentManagementSmokeServer.enabled())probe=value;}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("agent-management-evidence/client");}
    private static String q(Object value){return JSON.toJson(value);}
    private static void require(boolean ok,String code){if(!ok)throw new IllegalStateException(code);}
    private static void script(String value){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+value+"})();",h.browser().getURL(),0);}
    private static void action(String key,String code){script("window.__mg??={};if(window.__mg["+q(key)+"])return;const ready=(()=>{"+code+"})();if(ready)window.__mg["+q(key)+"]=true;");}
    private static void capture(String name,Runnable next){busy=true;var mc=Minecraft.getInstance();net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve(name+".png"));mc.execute(()->{busy=false;next.run();});}catch(Exception e){mc.execute(()->{busy=false;throw new IllegalStateException(e);});}});}
    private static void wire(String file,String action,Map<String,String> args,UUID operation,java.util.function.Consumer<Receipt> check){busy=true;UiClientSessions.command(action,args,operation).whenComplete((r,e)->Minecraft.getInstance().execute(()->{try{busy=false;if(e!=null)throw new IllegalStateException(e);Files.writeString(root().resolve(file+".json"),JSON.toJson(r));check.accept(r);}catch(Exception failure){AgentManagementSmokeServer.failure=failure.toString();}}));}
    private static String card(){return "document.querySelector('[data-agent-card=\""+AgentManagementSmokeServer.agent+"\"]')";}
    private static void reset(){previousSession=UiClientSessions.current().sessionId();WebGuiHostAdapter.INSTANCE.close();UiClientSessions.reset(true);resetAt=ticks;}
    private static boolean reopen(){if(ticks-resetAt<20)return false;WebGuiHostAdapter.INSTANCE.open();return WebGuiHostAdapter.INSTANCE.ready()&&UiClientSessions.current()!=null&&!UiClientSessions.current().sessionId().equals(previousSession);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!AgentManagementSmokeServer.enabled()||finished)return;var mc=Minecraft.getInstance();ticks++;Files.createDirectories(root());var host=WebGuiHostAdapter.INSTANCE;
        if(!AgentManagementSmokeServer.failure.isEmpty()||ticks>3000){finished=true;Files.writeString(root().resolve("failure.json"),JSON.toJson(Map.of("phase",phase,"error",AgentManagementSmokeServer.failure,"probe",probe==null?new JsonObject():probe)));host.close();mc.stop();throw new IllegalStateException("AGENT_MANAGEMENT_NATIVE_FAILED");}
        if(AgentManagementSmokeServer.done){finished=true;Files.writeString(root().resolve("result.json"),JSON.toJson(Map.of("status","REAL_DOM_MANAGEMENT_AND_REPLAN_FLOW","phase",phase,"systemInputInjected",false,"paidModelCalls",0,"fullV1",false)));host.close();mc.stop();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_AGENT_MANAGEMENT_OK");return;}
        if(mc.player!=null&&!opened){opened=true;host.open();}
        if(busy)return;
        if(phase==4||phase==14){if(reopen()){if(phase==4)phase=5;else phase=15;}return;}
        if(!host.ready()||UiClientSessions.current()==null||ticks%10!=0)return;
        script("window.mineagentQuery({request:JSON.stringify({channel:'agentManagementProbe',text:document.body.innerText,consent:document.querySelector('#task-replan-consent')?.checked??null,replanDisabled:document.querySelector('#task-replan-submit')?.disabled??null,spectator:!!document.querySelector('[data-agent-card=\""+AgentManagementSmokeServer.agent+"\"] [data-state=SPECTATOR]'),peerDisabled:document.querySelector('[data-agent-card=\""+AgentManagementSmokeServer.peer+"\"] fieldset')?.disabled??null}),persistent:false,onSuccess(){},onFailure(){}});");
        switch(phase){
            case 0->{if(AgentManagementSmokeServer.step<1)return;action("create","document.querySelector('#open-agents').click();const b=document.querySelector('#agent-create-submit');if(!b||b.closest('fieldset').disabled)return false;const n=document.querySelector('#agent-create-name');n.value='界面伙伴';n.dispatchEvent(new Event('input',{bubbles:true}));const m=document.querySelector('#agent-create-mode');m.value='SURVIVAL';m.dispatchEvent(new Event('change',{bubbles:true}));b.click();return true;");if(createRequest!=null&&AgentManagementSmokeServer.agent!=null)phase=1;}
            case 1->{if(probe==null||!probe.get("peerDisabled").isJsonPrimitive()||!probe.get("peerDisabled").getAsBoolean())return;
                wire("peer-rejected","agent.manage",Map.of("kind","mode","agentId",AgentManagementSmokeServer.peer.toString(),"expectedRevision","0","mode","SURVIVAL","confirmed","true"),UUID.randomUUID(),r->{require(r.code()==Code.PERMISSION_DENIED,"PEER_NOT_REJECTED");AgentManagementSmokeServer.clientSeen.add("peer-rejected");phase=2;});
            }
            case 2->{action("rename","const c="+card()+";if(!c)return false;const n=c.querySelector('input[aria-label=\"AI 名称\"]');n.value='伙伴改名';n.dispatchEvent(new Event('input',{bubbles:true}));c.querySelector('[data-agent-action=rename]').click();return true;");if(AgentManagementSmokeServer.step>=3)phase=3;}
            case 3->{if(probe==null||!probe.get("spectator").getAsBoolean())return;Files.writeString(root().resolve("spectator-ui.json"),JSON.toJson(probe));capture("manager-spectator",()->{AgentManagementSmokeServer.clientSeen.add("spectator-ui");reset();phase=4;});}
            case 5->wire("positive-create-replay","agent.manage",createRequest.arguments(),createRequest.operationId(),r->{require(r.code()==Code.APPLIED&&r.values().get("agentId").equals(AgentManagementSmokeServer.agent.toString()),"CREATE_REPLAY_CHANGED_ID");AgentManagementSmokeServer.clientSeen.add("positive-create-replay");phase=6;});
            case 6->{action("mode","document.querySelector('#open-agents').click();const c="+card()+";if(!c||c.querySelector('fieldset').disabled)return false;const m=c.querySelector('select[aria-label=\"请求模式\"]');m.value='SURVIVAL';m.dispatchEvent(new Event('change',{bubbles:true}));c.querySelector('[data-agent-action=mode]').click();c.querySelector('.agent-confirm input').checked=true;c.querySelector('[data-agent-action=confirm]').click();return true;");if(AgentManagementSmokeServer.step>=4)phase=7;}
            case 7->{action("collab-add","const c="+card()+";if(!c||c.querySelector('fieldset').disabled)return false;c.querySelector('details').open=true;c.querySelector('[aria-label=\"离线协作者 UUID\"]').value="+q(AgentManagementSmokeServer.otherOwner.toString())+";[...c.querySelectorAll('button')].find(b=>b.textContent==='添加协作者').click();return true;");if(AgentManagementSmokeServer.step>=5)phase=8;}
            case 8->{action("collab-remove","const c="+card()+";if(!c||c.querySelector('fieldset').disabled)return false;c.querySelector('details').open=true;const b=c.querySelector('details .actions button');if(!b)return false;b.click();return true;");if(AgentManagementSmokeServer.step>=6)phase=9;}
            case 9->{if(!AgentManagementSmokeServer.sourceRunning)return;action("pause","document.querySelector('#open-tasks').click();const b=document.querySelector('[data-world-task=\""+AgentManagementSmokeServer.sourceTask+"\"] [data-task-control=pause]');if(!b)return false;b.click();return true;");if(AgentManagementSmokeServer.step>=7)phase=10;}
            case 10->{action("replan-open","const b=document.querySelector('[data-world-task=\""+AgentManagementSmokeServer.sourceTask+"\"] [data-task-control=replan]');if(!b)return false;b.click();return true;");
                if(probe!=null&&probe.has("consent")&&probe.get("consent").isJsonPrimitive()&&!probe.get("consent").getAsBoolean()&&probe.get("replanDisabled").getAsBoolean()){
                    require(!Files.exists(mc.gameDirectory.toPath().resolve("agent-management-provider-count.json")),"MODEL_BEFORE_CONFIRMATION");Files.writeString(root().resolve("before-confirmation.json"),JSON.toJson(probe));capture("replan-confirmation",()->phase=11);
                }
            }
            case 11->{action("replan-submit","const g=document.querySelector('#task-replan-goal');if(!g)return false;g.value='保留黑曜石，移动到 (930,170,5)';g.dispatchEvent(new Event('input',{bubbles:true}));const n=document.querySelector('#task-replan-note');n.value='先重新观察当前身体与世界，不要回放旧挖掘动作。';n.dispatchEvent(new Event('input',{bubbles:true}));const c=document.querySelector('#task-replan-consent');c.checked=true;c.dispatchEvent(new Event('change',{bubbles:true}));document.querySelector('#task-replan-submit').click();return true;");if(replanRequest!=null)phase=12;}
            case 12->{if(AgentManagementSmokeServer.step<9)return;Files.writeString(root().resolve("after-replan.json"),JSON.toJson(probe));wire("replan-duplicate","task.replan",replanRequest.arguments(),replanRequest.operationId(),r->{require(r.code()==Code.ACCEPTED&&r.values().get("taskId").equals(AgentManagementSmokeServer.newTask.toString()),"REPLAN_DUPLICATED");AgentManagementSmokeServer.clientSeen.add("replan-duplicate");capture("replan-completed",()->phase=13);});}
            case 13->{action("delete","document.querySelector('#open-agents').click();const c="+card()+";if(!c||c.querySelector('fieldset').disabled)return false;c.querySelector('[data-agent-action=delete]').click();c.querySelector('.agent-confirm input').checked=true;c.querySelector('[data-agent-action=confirm]').click();return true;");if(AgentManagementSmokeServer.step>=10){reset();phase=14;}}
            case 15->wire("deleted-create-replay","agent.manage",createRequest.arguments(),createRequest.operationId(),r->{require(r.code()==Code.FAILED&&"AGENT_CREATION_REMOVED".equals(r.values().get("errorCode")),"DELETED_CREATE_RESURRECTED");AgentManagementSmokeServer.clientSeen.add("deleted-create-replay");phase=16;});
            default->{}
        }
    }
}
