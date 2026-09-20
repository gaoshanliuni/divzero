package dev.mineagent.runtime.neoforge.client.webui;
import dev.mineagent.runtime.neoforge.task.WorldActionSmokeServer;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class WorldActionSmokeClient {
    private static boolean opened,backup,captured,done;private static int ticks;private static JsonObject probe;
    public static void accept(JsonObject p){if(Boolean.getBoolean("mineagent.worldActionSmoke"))probe=p;}
    private static String q(String s){return new Gson().toJson(s);}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve(WorldActionSmokeServer.directory());}
    private static void main(String script){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+script+"})();",h.browser().getURL(),0);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.worldActionSmoke")||done)return;var mc=Minecraft.getInstance();ticks++;var h=WebGuiHostAdapter.INSTANCE;
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen s){backup=true;var f=s.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(s)).proceed(false,false);}
        if(mc.player!=null&&!opened){opened=true;mc.options.pauseOnLostFocus=false;h.open();}
        if(h.ready()&&UiClientSessions.current()!=null&&ticks%20==0){
            main("if(!document.querySelector('#world-task-prompt'))document.querySelector('#open-tasks').click();window.mineagentQuery({request:JSON.stringify({channel:'worldActionProbe',text:document.querySelector('#world-task-list')?.textContent??'',status:document.querySelector('#status').textContent}),persistent:false,onSuccess(){},onFailure(){}});");
            if(WorldActionSmokeServer.agentId!=null&&System.getProperty("mineagent.worldActionPlan","").isBlank()&&System.getProperty("mineagent.worldActionRestart","").isBlank())main("const agent=document.querySelector('#world-task-agent');if(agent&&[...agent.options].some(o=>o.value==="+q(WorldActionSmokeServer.agentId)+")&&!window.__worldTaskSubmitted){window.__worldTaskSubmitted=true;agent.value="+q(WorldActionSmokeServer.agentId)+";const text=document.querySelector('#world-task-prompt');text.value="+q(WorldActionSmokeServer.PROMPT)+";text.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#world-task-start').click();}");
            if(WorldActionSmokeServer.cancelTaskId!=null)main("const button=document.querySelector('[data-world-task=\""+WorldActionSmokeServer.cancelTaskId+"\"] [data-task-control=cancel]');if(button&&!window.__worldTaskCancelled){window.__worldTaskCancelled=true;button.scrollIntoView({block:'center'});button.click();}");
        }
        if(WorldActionSmokeServer.liveVerified&&!captured){captured=true;Files.createDirectories(root());Files.writeString(root().resolve("live-dom.json"),new Gson().toJson(probe));net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("live.png"));}catch(Exception e){throw new IllegalStateException(e);}});}
        if(WorldActionSmokeServer.finished){done=true;Files.writeString(root().resolve("final-dom.json"),new Gson().toJson(probe));h.close();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_WORLD_ACTION_SMOKE_OK run={}",WorldActionSmokeServer.RUN);mc.stop();}
        if(WorldActionSmokeServer.failure!=null||ticks>12000){done=true;Files.createDirectories(root());Files.writeString(root().resolve("client-failure.json"),new Gson().toJson(java.util.Map.of("code",WorldActionSmokeServer.failure==null?"TIMEOUT":WorldActionSmokeServer.failure,"probe",probe==null?new JsonObject():probe)));h.close();mc.stop();}
    }
}
