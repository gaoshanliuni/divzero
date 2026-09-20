package dev.mineagent.runtime.neoforge.client.webui;
import dev.mineagent.runtime.neoforge.ui.DecisionFlowSmokeServer;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class DecisionFlowSmokeClient {
    private static final Gson JSON=new Gson();private static boolean opened,backup,busy,ended;private static int ticks,phase,after;private static String current,failed;private static JsonObject probe;
    private static int probeTick;
    public static void accept(JsonObject p){probe=p;probeTick=ticks;}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve(DecisionFlowSmokeServer.directory());}
    public static void receipt(Request request,Receipt receipt){if(!Boolean.getBoolean("mineagent.decisionFlowSmoke"))return;try{Files.createDirectories(root());Files.writeString(root().resolve("receipt-"+request.operationId()+".json"),JSON.toJson(Map.of("request",request,"receipt",receipt)));}catch(Exception e){throw new IllegalStateException(e);}}
    @SubscribeEvent public static void tick(ClientTickEvent.Post e)throws Exception{
        if(!Boolean.getBoolean("mineagent.decisionFlowSmoke")||ended)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen s){backup=true;var f=s.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(s)).proceed(false,false);}
        if(mc.player!=null&&!opened){opened=true;mc.options.pauseOnLostFocus=false;host.open();}
        String scenario=DecisionFlowSmokeServer.currentCase,id=DecisionFlowSmokeServer.decisionId;
        if(scenario!=null&&!scenario.equals(current)){current=scenario;phase=0;probe=null;busy=false;after=ticks+25;}
        if(host.ready()&&UiClientSessions.current()!=null&&id!=null&&ticks>=after&&ticks%10==0){
            main("const card=document.querySelector('[data-decision-id=\""+id+"\"]');window.mineagentQuery({request:JSON.stringify({channel:'decisionFlowProbe',scenario:"+q(scenario)+",present:!!card,revision:card?.dataset.revision??'',text:card?.querySelector('textarea')?.value??'',selected:card?[...card.querySelectorAll('input:checked')].map(i=>i.value):[],status:card?.querySelector('[role=status]')?.textContent??'',pageLabel:document.querySelector('#decision-page-label').textContent,screenStatus:document.querySelector('#status').textContent}),persistent:false,onSuccess(){},onFailure(){}});");
            if(phase==0){main("document.querySelector('#open-decisions').click();");phase=1;after=ticks+20;}
            else if(phase==1&&probe!=null&&!probe.get("present").getAsBoolean()){
                // The new server case can precede shell.read delivery. Retry opening, never submitting.
                main("document.querySelector('#open-decisions').click();");after=ticks+20;
            }
            else if(phase==1&&probe!=null&&probe.get("present").getAsBoolean()){
                focusCard(id);
                String prefix="const card=document.querySelector('[data-decision-id=\""+id+"\"]');";
                switch(scenario){
                    case "checkpoint"->{main(prefix+"card.querySelector('[data-option-id=c]').click();"+setText("重启草稿不自动提交"));phase=50;after=ticks+40;}
                    case "restart"->{if(probe.get("text").getAsString().equals("重启草稿不自动提交")&&probe.getAsJsonArray("selected").toString().contains("c")){Files.writeString(root().resolve("restored-draft-dom.json"),JSON.toJson(probe));main(prefix+"card.querySelector('[data-ai-id=decision-submit]').click();");phase=51;}}
                    case "multi"->{main(prefix+"for(const id of ['a','b','c'])card.querySelector('[data-option-id='+id+']').click();card.querySelector('[data-ai-id=decision-submit]').click();");phase=2;after=ticks+20;}
                    case "text"->{main(prefix+setText("现场新方案：保留原结构，改成圆形入口")+"card.querySelector('[data-ai-id=decision-submit]').click();");phase=8;}
                    case "defer"->{main(prefix+"card.querySelector('[data-option-id=b]').click();"+setText("稍后保留草稿")+"[...card.querySelectorAll('button')].find(b=>b.textContent==='稍后决定').click();");phase=3;}
                    case "escape"->{main(prefix+"card.querySelector('[data-option-id=b]').click();"+setText("Esc 保留草稿"));phase=4;after=ticks+20;}
                    case "chat"->{chat(id,"第二个但不要透明",true);phase=8;}
                    case "race"->{chat(id,"第二个但保留聊天",false);main(prefix+"card.querySelector('[data-option-id=a]').click();"+setText("UI 并发回答")+"card.querySelector('[data-ai-id=decision-submit]').click();document.querySelector('#decision-flow-chat-send').click();");phase=8;}
                    case "cancel"->{main(prefix+"[...card.querySelectorAll('button')].find(b=>b.textContent==='取消关联任务').click();");phase=8;}
                    case "auth"->{chat(id,"第一个，同意",true);phase=5;after=ticks+30;}
                    case "stale"->{if(DecisionFlowSmokeServer.staleReady){negative(id,1,List.of("a"),"旧版本","STALE_REVISION");phase=6;after=ticks+20;}}
                }
            }else if(phase==2&&scenario.equals("multi")){
                negative(id,1,List.of("a","b","c"),"","TOO_MANY_SELECTIONS");main("const card=document.querySelector('[data-decision-id=\""+id+"\"]');card.querySelector('[data-option-id=c]').click();"+setText("多选补充意见")+"card.querySelector('[data-ai-id=decision-submit]').click();");phase=8;
            }else if(phase==3&&DecisionFlowSmokeServer.deferReady){
                if(!probe.get("text").getAsString().equals("稍后保留草稿"))throw new IllegalStateException("DEFER_UI_DRAFT_LOST");main("const card=document.querySelector('[data-decision-id=\""+id+"\"]');[...card.querySelectorAll('button')].find(b=>b.textContent==='继续回答').click();");phase=7;after=ticks+25;
            }else if(phase==4&&scenario.equals("escape")){
                if(mc.screen instanceof WebGuiInteractionScreen screen){screen.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,0));phase=40;after=ticks+30;}
            }else if(phase==40){if(mc.screen!=null)throw new IllegalStateException("ESC_DID_NOT_RELEASE");Files.writeString(root().resolve("escape-hidden.json"),JSON.toJson(probe));host.open();phase=41;after=ticks+20;}
            else if(phase==50){Files.writeString(root().resolve("checkpoint-draft-dom.json"),JSON.toJson(probe));main("document.querySelector('#decision-next').click();");phase=52;after=ticks+30;}
            else if(phase==52&&probe.get("pageLabel").getAsString().startsWith("2/")){
                Files.writeString(root().resolve("history-page-two.json"),JSON.toJson(probe));main("document.querySelector('#decision-prev').click();");phase=53;after=ticks+30;
            }
            else if(phase==53&&probe.get("pageLabel").getAsString().startsWith("1/")){
                if(!probe.get("text").getAsString().equals("重启草稿不自动提交"))throw new IllegalStateException("PAGING_DRAFT_LOST");
                Files.writeString(root().resolve("history-page-return.json"),JSON.toJson(probe));DecisionFlowSmokeServer.checkpointReady=true;phase=51;
            }
            else if(phase==41){main("document.querySelector('#open-decisions').click();");phase=7;after=ticks+20;}
            else if(phase==5&&scenario.equals("auth")){negative(id,1,List.of(),"自由建议不是授权","AUTHORIZATION_CHOICE_REQUIRED");main("const card=document.querySelector('[data-decision-id=\""+id+"\"]');card.querySelector('[data-option-id=b]').click();"+setText("本次拒绝")+"card.querySelector('[data-ai-id=decision-submit]').click();");phase=8;}
            else if(phase==6&&scenario.equals("stale")){main("const card=document.querySelector('[data-decision-id=\""+id+"\"]');[...card.querySelectorAll('button')].find(b=>b.textContent==='继续回答').click();");phase=7;after=ticks+25;}
            else if(phase==7){main("const card=document.querySelector('[data-decision-id=\""+id+"\"]');"+(scenario.equals("stale")?"card.querySelector('[data-option-id=c]').click();":"")+"card.querySelector('[data-ai-id=decision-submit]').click();");phase=8;}
        }
        if(phase==8&&DecisionFlowSmokeServer.caseVerified&&!busy){focusCard(id);phase=80;after=ticks+25;}
        if(phase==80&&probeTick>after&&probe!=null&&current.equals(probe.get("scenario").getAsString())&&!busy){busy=true;Files.createDirectories(root());Files.writeString(root().resolve(current+"-dom.json"),JSON.toJson(probe));
            net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve(current+".png"));phase=9;busy=false;}catch(Exception ex){failed=ex.toString();}});
        }
        if(phase==9){main("document.querySelector('[data-view-id=\"decision-"+id+"\"] [aria-label=关闭]')?.click();document.querySelector('[data-view-id=runtime-chat] [aria-label=关闭]')?.click();");phase=10;DecisionFlowSmokeServer.next=true;}
        if(DecisionFlowSmokeServer.finished){ended=true;host.close();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_DECISION_FLOW_CLIENT_OK run={}",DecisionFlowSmokeServer.RUN);mc.stop();}
        if(failed!=null||ticks>7200){Files.createDirectories(root());Files.writeString(root().resolve("failure.json"),JSON.toJson(Map.of("phase",phase,"scenario",current==null?"":current,"failure",failed==null?"TIMEOUT":failed,"probe",probe==null?new JsonObject():probe)));ended=true;host.close();mc.stop();throw new IllegalStateException("DECISION_FLOW_FAILED "+failed);}
    }
    private static String q(String s){return JSON.toJson(s);}
    private static void focusCard(String id){main("const target=document.querySelector('[data-view-id=\"decision-"+id+"\"]');for(const other of document.querySelectorAll('[data-view-id^=decision-]'))if(other!==target&&getComputedStyle(other).display!=='none')other.querySelector('[aria-label=收起]')?.click();target?.querySelector('.content')?.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true}));");}
    private static String setText(String text){return "const text=card.querySelector('textarea');text.value="+q(text)+";text.dispatchEvent(new Event('input',{bubbles:true}));";}
    private static void main(String s){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+s+"})();",h.browser().getURL(),0);}
    private static void chat(String id,String text,boolean send){main("document.querySelector('#open-chat').click();const agent=document.querySelector('#chat-agent');agent.value="+q(DecisionFlowSmokeServer.agentId)+";agent.dispatchEvent(new Event('change',{bubbles:true}));document.querySelector('#chat-decision').value="+q(id)+";const text=document.querySelector('#chat-draft');text.value="+q(text)+";text.dispatchEvent(new Event('input',{bubbles:true}));const submit=[...document.querySelectorAll('[data-view-id=runtime-chat] button')].find(b=>b.textContent==='发送私密消息');submit.id='decision-flow-chat-send';"+(send?"submit.click();":""));}
    private static void negative(String id,long revision,List<String> selected,String custom,String expected){var args=new LinkedHashMap<String,String>();UUID op=UUID.randomUUID();args.put("decisionId",id);args.put("expectedRevision",Long.toString(revision));args.put("submissionId",op.toString());args.put("selectedCount",Integer.toString(selected.size()));for(int i=0;i<selected.size();i++)args.put("selected."+i,selected.get(i));args.put("customText",custom);
        UiClientSessions.command("decision.submit",args,op).whenComplete((receipt,error)->{if(error!=null||!expected.equals(receipt.values().get("errorCode")))failed="NEGATIVE_DECISION_NOT_REJECTED_"+expected;});}
}
