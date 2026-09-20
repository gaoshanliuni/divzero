package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.neoforge.ui.DesktopWindowsSmokeServer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;
/** Real CEF form handlers + native workspace toggle, without OS input. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class DesktopWindowsSmokeClient {
    private static int ticks,phase,after;private static boolean opened,finished,busy;private static JsonObject probe;private static Object originalBrowser;
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("desktop-windows-smoke");}
    public static void accept(JsonObject p){probe=p;}
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    private static void script(String code){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+code+"})();",h.browser().getURL(),0);}
    private static JsonObject window(String id){return probe.getAsJsonObject("windows").getAsJsonObject(id);}
    private static boolean visible(String id){return window(id)!=null&&window(id).get("visible").getAsBoolean();}
    private static boolean pinned(String id){return window(id)!=null&&window(id).get("pinned").getAsBoolean();}
    private static void snap(String name){busy=true;net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget(),img->{try(img){img.writeToFile(root().resolve(name+".png"));}catch(Exception e){DesktopWindowsSmokeServer.failure=e.toString();}finally{busy=false;}});}
    private static void poll(){script("const windows={};for(const n of document.querySelectorAll('.window'))windows[n.dataset.viewId]={visible:getComputedStyle(n).display!=='none',pinned:n.dataset.pinned==='true',inert:n.querySelector('.content').inert};window.mineagentQuery({request:JSON.stringify({channel:'desktopWindowProbe',windows,interacting:document.body.dataset.interacting,workspace:document.body.dataset.workspaceVisible,dockHeight:document.querySelector('#dock').getBoundingClientRect().height,primaryButtons:document.querySelectorAll('#dock>button:not([hidden])').length,menuOpen:document.querySelector('#more-menu').open,menuVisible:document.querySelector('#open-status').getBoundingClientRect().height>0,taskButtons:document.querySelectorAll('#minimized button').length,aiReady:!!document.querySelector('#desktop-agent')?.value,aiDisabled:document.querySelector('#desktop-ai-start')?.disabled??true,aiStatus:document.querySelector('#desktop-ai-status')?.textContent||'',sameChat:document.querySelector('[data-view-id=runtime-chat]')===window.__desktopChat,draft:document.querySelector('#echo-input')?.value||'',status:document.querySelector('#status').textContent}),persistent:false,onSuccess(){},onFailure(){}});");}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.desktopWindowsSmoke")||finished)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;
        try{Files.createDirectories(root());if(ticks>2300||!DesktopWindowsSmokeServer.failure.isEmpty())throw new IllegalStateException("DESKTOP_TIMEOUT_"+phase+" "+DesktopWindowsSmokeServer.failure);
            if(ticks%40==0)Files.writeString(root().resolve("progress.json"),new Gson().toJson(Map.of("phase",phase,"ticks",ticks,"probe",probe==null?new JsonObject():probe)));
            if(mc.player==null||!DesktopWindowsSmokeServer.ready)return;if(!opened){opened=true;host.open();}
            if(phase==12&&ticks>=after){host.open();phase=13;after=ticks+60;}
            if(!host.ready()||busy||ticks%10!=0)return;poll();if(probe==null||UiClientSessions.current()==null)return;
            switch(phase){
                case 0->{if(!visible("runtime-chat"))return;require(probe.get("primaryButtons").getAsInt()<=8&&!probe.get("menuVisible").getAsBoolean(),"DESKTOP_NOT_COMPACT");originalBrowser=host.browser();script("window.__desktopChat=document.querySelector('[data-view-id=runtime-chat]');document.querySelector('#more-menu').open=true;");phase=1;after=ticks+20;}
                case 1->{if(ticks<after)return;require(probe.get("menuVisible").getAsBoolean(),"DESKTOP_MENU_NOT_VISIBLE");snap("more-menu");script("document.querySelector('#open-status').click();document.querySelector('[data-view-id=runtime-chat] [data-action=pin-window]').click();document.querySelector('#echo-input').value='DESKTOP_DRAFT_KEEP';");phase=2;after=ticks+25;}
                case 2->{if(ticks<after)return;require(pinned("runtime-chat")&&visible("runtime-status")&&!probe.get("menuOpen").getAsBoolean(),"DESKTOP_PIN_OR_MENU_CLOSE");host.toggleWorkspace();phase=3;after=ticks+30;}
                case 3->{if(ticks<after)return;require(visible("runtime-chat")&&!visible("runtime-status")&&window("runtime-chat").get("inert").getAsBoolean()&&mc.screen==null,"DESKTOP_GAMEPLAY_VISIBILITY");Files.writeString(root().resolve("gameplay.json"),probe.toString());snap("pinned-in-game");host.toggleWorkspace();phase=4;after=ticks+25;}
                case 4->{if(ticks<after)return;require(host.browser()==originalBrowser&&probe.get("sameChat").getAsBoolean()&&probe.get("draft").getAsString().equals("DESKTOP_DRAFT_KEEP"),"DESKTOP_RECREATED_WINDOWS");script("document.querySelector('[data-view-id=runtime-chat] [aria-label=收起]').click();");phase=5;after=ticks+20;}
                case 5->{if(ticks<after)return;require(!visible("runtime-chat")&&visible("runtime-status"),"DESKTOP_MINIMIZE_NOT_INDEPENDENT");script("document.querySelector('[data-window-task=runtime-chat]').click();document.querySelector('#open-windows').click();");phase=6;}
                case 6->{if(!probe.get("aiReady").getAsBoolean())return;require(visible("runtime-chat"),"DESKTOP_TASKBAR_RESTORE");script("const t=document.querySelector('#desktop-target');t.value='runtime-status';t.dispatchEvent(new Event('change'));document.querySelector('#desktop-ai-start').click();");phase=7;}
                case 7->{if(!probe.get("aiStatus").getAsString().contains("AI 已将"))return;require(pinned("runtime-status"),"DESKTOP_AI_PIN");script("const g=document.querySelector('#desktop-goal');g.value='unpin';g.dispatchEvent(new Event('change'));document.querySelector('#desktop-ai-start').click();");phase=8;}
                case 8->{if(!probe.get("aiStatus").getAsString().contains("AI 已取消"))return;require(!pinned("runtime-status"),"DESKTOP_AI_UNPIN");script("const g=document.querySelector('#desktop-goal');g.value='pin';g.dispatchEvent(new Event('change'));document.querySelector('#desktop-ai-start').click();");phase=9;after=ticks+10;}
                case 9->{if(ticks<after||!probe.get("aiDisabled").getAsBoolean())return;script("const b=document.querySelector('[data-view-id=runtime-status] [data-action=pin-window]');b.click();b.click();");phase=10;}
                case 10->{if(!probe.get("aiStatus").getAsString().contains("窗口状态已变化"))return;require(!pinned("runtime-status"),"DESKTOP_LATE_AI_OVERRIDE");Files.writeString(root().resolve("ai-and-independent.json"),probe.toString());snap("window-manager");phase=11;after=ticks+40;}
                case 11->{if(ticks<after)return;host.close();UiClientSessions.reset(true);phase=12;after=ticks+20;}
                case 12->{if(ticks<after)return;host.open();phase=13;after=ticks+60;}
                case 13->{if(ticks<after||!visible("runtime-chat")||!pinned("runtime-chat"))return;Files.writeString(root().resolve("restored.json"),probe.toString());Files.writeString(root().resolve("result.json"),new Gson().toJson(Map.of("status","DESKTOP_WINDOWS_NATIVE_VERIFIED","independentVisibility",true,"pinnedGameplayReadOnly",true,"aiPinAndUnpin",true,"lateAiRejected",true,"hostReopenPreferenceRestored",true,"paidCalls",0,"systemInputInjected",false)));finished=true;host.close();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_DESKTOP_WINDOWS_OK");mc.stop();}
            }
        }catch(Exception error){finished=true;Files.writeString(root().resolve("failure.json"),new Gson().toJson(Map.of("phase",phase,"error",error.toString(),"probe",probe==null?new JsonObject():probe)));host.close();mc.stop();}
    }
}
