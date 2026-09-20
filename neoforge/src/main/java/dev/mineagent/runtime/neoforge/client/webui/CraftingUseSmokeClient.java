package dev.mineagent.runtime.neoforge.client.webui;
import dev.mineagent.runtime.neoforge.task.CraftingUseSmokeServer;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class CraftingUseSmokeClient {
    private static boolean opened,backup,captured,done;private static int ticks;private static JsonObject probe;
    public static void accept(JsonObject p){if(Boolean.getBoolean("mineagent.craftingUseSmoke"))probe=p;}
    private static String q(String s){return new Gson().toJson(s);}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve(CraftingUseSmokeServer.directory());}
    private static void main(String script){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+script+"})();",h.browser().getURL(),0);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.craftingUseSmoke")||done)return;var mc=Minecraft.getInstance();ticks++;var h=WebGuiHostAdapter.INSTANCE;
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen s){backup=true;var f=s.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(s)).proceed(false,false);}
        if(mc.player!=null&&!opened){opened=true;mc.options.pauseOnLostFocus=false;h.open();}
        if(h.ready()&&UiClientSessions.current()!=null&&ticks%20==0){
            main("if(!document.querySelector('#world-task-prompt'))document.querySelector('#open-tasks').click();window.mineagentQuery({request:JSON.stringify({channel:'craftingUseProbe',text:document.querySelector('#world-task-list')?.textContent??'',status:document.querySelector('#status').textContent}),persistent:false,onSuccess(){},onFailure(){}});");
            if(CraftingUseSmokeServer.agentId!=null&&System.getProperty("mineagent.craftingUseReplay","").isBlank())main("const agent=document.querySelector('#world-task-agent');if(agent&&[...agent.options].some(o=>o.value==="+q(CraftingUseSmokeServer.agentId)+")&&!window.__worldTaskSubmitted){window.__worldTaskSubmitted=true;agent.value="+q(CraftingUseSmokeServer.agentId)+";const text=document.querySelector('#world-task-prompt');text.value="+q(CraftingUseSmokeServer.PROMPT)+";text.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#world-task-start').click();}");
            if(CraftingUseSmokeServer.cancelTaskId!=null)main("const button=document.querySelector('[data-world-task=\""+CraftingUseSmokeServer.cancelTaskId+"\"] [data-task-control=cancel]');if(button&&!window.__worldTaskCancelled){window.__worldTaskCancelled=true;button.scrollIntoView({block:'center'});button.click();}");
        }
        if(CraftingUseSmokeServer.liveVerified&&!captured){captured=true;Files.createDirectories(root());Files.writeString(root().resolve("live-dom.json"),new Gson().toJson(probe));net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("live.png"));}catch(Exception e){throw new IllegalStateException(e);}});}
        if(CraftingUseSmokeServer.finished){done=true;Files.writeString(root().resolve("final-dom.json"),new Gson().toJson(probe));h.close();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_CRAFTING_USE_SMOKE_OK run={}",CraftingUseSmokeServer.RUN);mc.stop();}
        if(CraftingUseSmokeServer.failure!=null||ticks>12000){done=true;Files.createDirectories(root());Files.writeString(root().resolve("client-failure.json"),new Gson().toJson(java.util.Map.of("code",CraftingUseSmokeServer.failure==null?"TIMEOUT":CraftingUseSmokeServer.failure,"probe",probe==null?new JsonObject():probe)));h.close();mc.stop();}
    }
}
