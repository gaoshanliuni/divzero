package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.neoforge.task.AppearanceAgentSmokeServer;
import dev.mineagent.runtime.neoforge.client.ysm.YsmRenderObserver;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class AppearanceAgentSmokeClient {
    private static boolean opened,backup,done,capturing,measuring;private static int ticks,measureAt;private static JsonObject probe;
    static void probe(JsonObject value){probe=value.deepCopy();}
    private static String q(String value){return new Gson().toJson(value);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!AppearanceAgentSmokeServer.enabled()||done)return;var mc=Minecraft.getInstance();var h=WebGuiHostAdapter.INSTANCE;ticks++;Path root=mc.gameDirectory.toPath().resolve("appearance-agent-evidence");Files.createDirectories(root);
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen screen){backup=true;var f=screen.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(screen)).proceed(false,false);}
        if(mc.player!=null&&!opened){opened=true;h.open();}
        if(h.ready()&&UiClientSessions.current()!=null&&ticks%10==0&&AppearanceAgentSmokeServer.agentId!=null){
            String script="""
                if(!document.querySelector('#world-task-prompt'))document.querySelector('#open-tasks').click();
                const agent=document.querySelector('#world-task-agent');
                if(!window.__appearanceTaskSent&&[...agent.options].some(o=>o.value===AGENT)){agent.value=AGENT;const input=document.querySelector('#world-task-prompt');input.value=PROMPT;input.dispatchEvent(new Event('input',{bubbles:true}));window.__appearanceTaskSent=true;document.querySelector('#world-task-start').click();}
                if(DECISION&&!window.__appearanceAnswerSent){document.querySelector('#open-decisions').click();const c=document.querySelector('[data-decision-id="'+DECISION+'"]');if(c&&ALLOW){const option=c.querySelector('input[data-option-id]');if(option)option.click();const text=c.querySelector('textarea');text.value=ANSWER;text.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text.value}));window.__appearanceAnswerSent=true;c.querySelector('[data-ai-id="decision-submit"]').click();}}
                window.mineagentQuery({request:JSON.stringify({channel:'appearanceAgentProbe',sent:!!window.__appearanceTaskSent,answered:!!window.__appearanceAnswerSent,text:document.body.innerText,task:document.querySelector('#world-task-list')?.innerText??''}),persistent:false,onSuccess(){},onFailure(){}});
                """.replace("AGENT",q(AppearanceAgentSmokeServer.agentId)).replace("PROMPT",q(AppearanceAgentSmokeServer.PROMPT)).replace("DECISION",AppearanceAgentSmokeServer.decisionId==null?"null":q(AppearanceAgentSmokeServer.decisionId)).replace("ALLOW",Boolean.toString(AppearanceAgentSmokeServer.answerAllowed)).replace("ANSWER",q(AppearanceAgentSmokeServer.ANSWER));
            h.browser().executeJavaScript("(()=>{"+script+"})();",h.browser().getURL(),0);
        }
        if(AppearanceAgentSmokeServer.completed&&probe!=null&&probe.get("answered").getAsBoolean()&&!capturing){
            if(!measuring){measuring=true;measureAt=ticks;YsmRenderObserver.expect(UUID.fromString(AppearanceAgentSmokeServer.agentId));return;}
            var r=YsmRenderObserver.snapshot();var nativeState=YsmRenderObserver.inspectExpectedClientState();if(ticks-measureAt<40||nativeState.filter(s->s.renderReady()&&s.modelId().equals("default")&&s.textureId().equals("blue")).isEmpty()||r.ysmCalls()<10)return;
            if(r.vanillaCalls()!=0||r.duplicateDraws()!=0)throw new IllegalStateException("AGENT_APPEARANCE_RENDER_PARITY");
            Files.writeString(root.resolve("gui.json"),probe.toString());Files.writeString(root.resolve("render.json"),new Gson().toJson(Map.of("frames",r.uniqueFrames(),"ysmDraws",r.ysmCalls(),"vanillaDraws",r.vanillaCalls(),"duplicateDraws",r.duplicateDraws(),"native",nativeState.orElseThrow())));
            capturing=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root.resolve("gui.png"));done=true;h.close();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_APPEARANCE_AGENT_OK");mc.stop();}catch(Exception e){throw new IllegalStateException(e);}});
        }
        if(AppearanceAgentSmokeServer.failure!=null||ticks>6500){Files.writeString(root.resolve("client-failure.json"),new Gson().toJson(Map.of("error",AppearanceAgentSmokeServer.failure==null?"TIMEOUT":AppearanceAgentSmokeServer.failure,"probe",probe==null?new JsonObject():probe)));done=true;h.close();mc.stop();}
    }
}
