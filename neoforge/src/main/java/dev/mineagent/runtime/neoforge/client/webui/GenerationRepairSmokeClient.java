package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.nio.file.*;
import java.util.*;

/** Normal CEF controls via a Java fixture, never OS mouse/keyboard input. */
final class GenerationRepairSmokeClient {
    private static final Gson JSON=new Gson();private static JsonObject probe;private static int phase,ticks,after;private static boolean busy,ended;
    static boolean enabled(){return Boolean.getBoolean("mineagent.generationRepairSmoke");}
    static void accept(JsonObject message){if(enabled())probe=message.deepCopy();}
    private static void main(String script){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+script+"})();",h.browser().getURL(),0);}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("generation-repair-evidence");}
    static boolean tick(boolean trusted,JsonObject info)throws Exception{
        if(!enabled())return false;if(ended)return true;ticks++;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;
        if(ticks>3000)throw new IllegalStateException("GENERATION_REPAIR_CLIENT_TIMEOUT_"+phase);if(info==null)return true;
        boolean owner=info.get("owner").getAsBoolean();
        if(phase==0&&trusted&&info.get("ready").getAsBoolean()){host.open();phase=1;}
        if(phase==1&&host.ready()&&UiClientSessions.current()!=null){main("document.querySelector('#open-generation').click();");phase=2;}
        if(phase>=2&&phase<8&&ticks%10==0){main("const source="+JSON.toJson(owner&&info.has("sourceOperation")?info.get("sourceOperation").getAsString():"")+";window.mineagentQuery({request:JSON.stringify({channel:'generationRepairProbe',sourceButton:!!document.querySelector('[data-repair-generation=\"'+source+'\"]'),ownedJobs:document.querySelectorAll('.generation-job[data-operation-id]').length,form:!!document.querySelector('#generation-repair-prompt'),confirmed:!!document.querySelector('#generation-repair-consent')?.checked,disabled:document.querySelector('#generation-repair-submit')?.disabled??true,receiptTask:document.querySelector('#generation-repair-receipt')?.dataset.taskId??'',body:document.querySelector('[data-view-id=runtime-generation-repair]')?.innerText??'',error:document.querySelector('#status')?.textContent??''}),persistent:false,onSuccess(){},onFailure(){}});");}
        if(phase==2&&probe!=null&&info.get("sourceReady").getAsBoolean()){
            if(!owner){if(probe.get("ownedJobs").getAsInt()!=0)throw new IllegalStateException("REPAIR_PEER_JOB_LEAK");phase=6;}
            else if(probe.get("sourceButton").getAsBoolean()){main("document.querySelector('[data-repair-generation=\""+info.get("sourceOperation").getAsString()+"\"]').click();const input=document.querySelector('#generation-repair-prompt');input.value="+JSON.toJson(info.get("instructions").getAsString())+";input.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#generation-repair-submit').click();");phase=3;after=ticks+40;}
        }
        if(phase==3&&ticks>=after&&probe!=null){
            if(!probe.get("form").getAsBoolean()||probe.get("confirmed").getAsBoolean()||!probe.get("disabled").getAsBoolean()||info.get("calls").getAsInt()!=1)throw new IllegalStateException("REPAIR_SUBMITTED_WITHOUT_CONSENT");
            Files.createDirectories(root());Files.writeString(root().resolve("consent-before.json"),JSON.toJson(probe));main("document.querySelector('#generation-repair-consent').click();document.querySelector('#generation-repair-submit').click();");phase=4;
        }
        if(phase==4&&info.has("repairedTask")&&probe!=null&&probe.get("receiptTask").getAsString().equals(info.get("repairedTask").getAsString())){phase=6;after=ticks+40;}
        if(phase==6&&info.get("verified").getAsBoolean()&&ticks>=after&&!busy){busy=true;Files.createDirectories(root());Files.writeString(root().resolve("client-proof.json"),JSON.toJson(Map.of("info",info,"probe",probe,"systemInputInjected",false,"realModelCalls",0)));
            net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("repair-game.png"));mc.execute(()->{ClientPacketDistributor.sendToServer(new UiPayloads.Command(UUID.randomUUID(),"deliveryFixture","{\"action\":\"done\"}"));phase=8;busy=false;after=ticks+20;});}catch(Exception e){mc.execute(()->{throw new IllegalStateException("REPAIR_SCREENSHOT",e);});}});
        }
        if(phase==8&&ticks>=after){ended=true;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_DELIVERY_CLIENT_OK role={} generationRepair=true",System.getProperty("mineagent.deliverySmokeRole"));host.close();mc.stop();}return true;
    }
}
