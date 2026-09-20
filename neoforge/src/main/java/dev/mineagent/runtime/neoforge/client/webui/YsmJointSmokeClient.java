package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.neoforge.ui.YsmJointSmokeServer;
import dev.mineagent.runtime.neoforge.client.ysm.YsmRenderObserver;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;

@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class YsmJointSmokeClient {
    public static volatile boolean decisionsDone;
    private static int ticks,phase,after;private static boolean initialized,busy,done,measuring;private static JsonObject probe;private static long memoryBefore;
    public static void probe(JsonObject value){probe=value;}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("ysm-joint-evidence");}
    private static void main(String code){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+code+"})();",h.browser().getURL(),0);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.ysmJointSmoke")||done)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;
        if(decisionsDone&&YsmJointSmokeServer.agentId!=null&&host.ready()){
            if(!initialized){initialized=true;YsmRenderObserver.expect(YsmJointSmokeServer.agentId);memoryBefore=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();}
            boolean choices=Boolean.getBoolean("mineagent.appearanceChoiceSmoke");
            if(phase==0&&ticks%10==0&&choices)main(YsmAppearanceChoiceSmoke.script(new Gson().toJson(YsmJointSmokeServer.agentId.toString())));
            if(phase==0&&ticks%10==0&&!choices)main("""
                document.querySelector('#open-appearance').click();const agent=document.querySelector('#appearance-agent');
                if(!window.__jointSelected&&[...agent.options].some(o=>o.value===AGENT)){window.__jointSelected=true;agent.value=AGENT;agent.dispatchEvent(new Event('change',{bubbles:true}));}
                const apply=document.querySelector('#appearance-apply');if(!apply||apply.disabled||window.__jointApplied)return;
                window.__jointApplied=true;for(const [key,value] of Object.entries({model:'default',texture:'blue',animation:'idle'})){const f=document.querySelector('#appearance-'+key);f.value=value;f.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:value}));}apply.click();
                """.replace("AGENT",new Gson().toJson(YsmJointSmokeServer.agentId.toString())));
            if(ticks%10==0)main("window.mineagentQuery({request:JSON.stringify({channel:'ysmJointProbe',choices:window.__choiceSmoke??{},text:document.body.innerText,applied:document.querySelector('#appearance-fields')?.dataset.appearanceRevision??'',model:document.querySelector('#appearance-model')?.value??'',texture:document.querySelector('#appearance-texture')?.value??'',applyDisabled:!!document.querySelector('#appearance-apply')?.disabled,decideDisabled:!!document.querySelector('#appearance-decide')?.disabled}),persistent:false,onSuccess(){},onFailure(){}});");
            if(choices&&probe!=null&&probe.has("choices")&&probe.getAsJsonObject("choices").has("error")&&!probe.getAsJsonObject("choices").get("error").getAsString().isEmpty()){
                Files.createDirectories(root());Files.writeString(root().resolve("choice-failure.json"),probe.toString());done=true;host.close();mc.stop();throw new IllegalStateException("APPEARANCE_CHOICE_FAILED");
            }
            var observed=YsmRenderObserver.inspectExpectedClientState();var render=YsmRenderObserver.snapshot();
            if(phase==0&&Boolean.getBoolean("mineagent.ysmJointAbsent")&&probe!=null&&probe.get("text").getAsString().contains("YSM_ABSENT")&&probe.get("applyDisabled").getAsBoolean()&&probe.get("decideDisabled").getAsBoolean()){
                Files.createDirectories(root());Files.writeString(root().resolve("absent-state.json"),probe.toString());phase=1;after=ticks+30;
            }
            if(!measuring&&(!choices||YsmJointSmokeServer.choicesVerified)&&YsmJointSmokeServer.applied&&probe!=null&&!probe.get("applied").getAsString().isBlank()&&observed.filter(s->s.renderReady()&&s.modelId().equals("default")&&s.textureId().equals("blue")).isPresent()){
                Files.createDirectories(root());Files.writeString(root().resolve("transition-render.json"),new Gson().toJson(Map.of("ysmCalls",render.ysmCalls(),"vanillaCalls",render.vanillaCalls(),"duplicateDraws",render.duplicateDraws(),"note","Includes pre-application frames; not the ready-state duplicate-render verdict")));
                measuring=true;after=ticks+60;YsmRenderObserver.expect(YsmJointSmokeServer.agentId);return;
            }
            if(phase==0&&measuring&&ticks>=after&&YsmJointSmokeServer.applied&&probe!=null&&!probe.get("applied").getAsString().isBlank()&&observed.filter(s->s.renderReady()&&s.modelId().equals("default")&&s.textureId().equals("blue")).isPresent()&&render.ysmCalls()>=10){
                if(render.vanillaCalls()!=0||render.duplicateDraws()!=0)throw new IllegalStateException("YSM_DUPLICATE_RENDER");
                Files.createDirectories(root());Files.writeString(root().resolve("gui-state.json"),probe.toString());Files.writeString(root().resolve("render.json"),new Gson().toJson(Map.of("state",observed.orElseThrow(),"ysmCalls",render.ysmCalls(),"vanillaCalls",render.vanillaCalls(),"duplicateDraws",render.duplicateDraws(),"frames",render.uniqueFrames(),"memoryDelta",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()-memoryBefore,"browserReady",host.ready())));phase=1;after=ticks+20;
            }
            if(phase==1&&ticks>=after&&!busy){busy=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("webgui-ysm.png"));phase=2;after=ticks+20;busy=false;}catch(Exception e){throw new IllegalStateException(e);}});}
            if(phase==2&&ticks>=after&&!busy){host.close();phase=3;after=ticks+30;}
        }
        if(phase==3&&ticks>=after&&!busy){busy=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("world-ysm.png"));phase=4;busy=false;}catch(Exception e){throw new IllegalStateException(e);}});}
        if(phase==4){done=true;boolean absent=Boolean.getBoolean("mineagent.ysmJointAbsent");Files.writeString(root().resolve("result.json"),new Gson().toJson(Map.of("status",absent?"YSM_ABSENT_WEBGUI_VERIFIED":"YSM_WEBGUI_JOINT_VERIFIED","providerCalls",0,"fullV1",false)));dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info(absent?"MINEAGENT_YSM_ABSENT_GUI_OK":"MINEAGENT_YSM_WEBGUI_JOINT_OK");mc.stop();}
        if(ticks>6000){Files.createDirectories(root());Files.writeString(root().resolve("failure.json"),new Gson().toJson(Map.of("phase",phase,"probe",probe==null?new JsonObject():probe,"render",YsmRenderObserver.snapshot().ysmCalls())));done=true;host.close();mc.stop();throw new IllegalStateException("YSM_WEBGUI_TIMEOUT");}
    }
}
