package dev.mineagent.runtime.neoforge.client.webui;
import dev.mineagent.runtime.neoforge.content.WorldContentSmokeServer;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class WorldContentSmokeClient {
    private static final Gson JSON=new Gson();private static int ticks,phase,after;private static boolean opened,backup,done,capturing;private static JsonObject probe;
    public static void accept(JsonObject value){if(Boolean.getBoolean("mineagent.worldContentSmoke"))probe=value;}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve(WorldContentSmokeServer.directory());}
    private static void main(String code){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+code+"})();",h.browser().getURL(),0);}
    private static String q(String s){return JSON.toJson(s);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post e)throws Exception{
        if(!Boolean.getBoolean("mineagent.worldContentSmoke")||done)return;var mc=Minecraft.getInstance();var h=WebGuiHostAdapter.INSTANCE;ticks++;
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen s){backup=true;var f=s.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(s)).proceed(false,false);}
        if(!System.getProperty("mineagent.worldContentVerifyRestart","").isBlank()){
            if(WorldContentSmokeServer.restartVerified&&!capturing){capturing=true;Files.createDirectories(root());net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("restart-world.png"));done=true;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_WORLD_CONTENT_RESTART_OK run={}",WorldContentSmokeServer.RUN);mc.stop();}catch(Exception ex){throw new IllegalStateException(ex);}});}
            if(ticks>2400){done=true;mc.stop();throw new IllegalStateException("WORLD_RESTART_TIMEOUT");}return;
        }
        if(mc.player!=null&&!opened){opened=true;mc.options.pauseOnLostFocus=false;h.open();}
        if(h.ready()&&UiClientSessions.current()!=null&&ticks%20==0){
            main("window.mineagentQuery({request:JSON.stringify({channel:'worldContentProbe',status:document.querySelector('#status').textContent,text:[...document.querySelectorAll('[data-world-activate]')].map(b=>b.closest('.content').textContent).join('\\n')}),persistent:false,onSuccess(){},onFailure(){}});");
            if(phase==0&&!System.getProperty("mineagent.worldContentResume","").isBlank())phase=1;
            if(phase==0&&WorldContentSmokeServer.agentId!=null){main("if(!document.querySelector('#generation-prompt'))document.querySelector('#open-generation').click();const agent=document.querySelector('#generation-agent');if(![...agent.options].some(o=>o.value==="+q(WorldContentSmokeServer.agentId)+"))return;if(!window.__worldSubmitted){window.__worldSubmitted=true;agent.value="+q(WorldContentSmokeServer.agentId)+";const purpose=document.querySelector('#generation-purpose');purpose.value='WORLD_CONTENT';purpose.dispatchEvent(new Event('change',{bubbles:true}));const p=document.querySelector('#generation-prompt');p.value="+q(WorldContentSmokeServer.PROMPT)+";p.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#generation-submit').click();}");if(WorldContentSmokeServer.operationId!=null)phase=1;}
            if((phase==1||phase==4)&&WorldContentSmokeServer.packageId!=null){String pkg=WorldContentSmokeServer.packageId;
                main("if(!document.querySelector('#generation-prompt'))document.querySelector('#open-generation').click();const open=document.querySelector('[data-world-package=\""+pkg+"\"]');if(!open)return;open.click();const start=document.querySelector('[data-world-activate=\""+pkg+"\"]');if(!start)return;const content=start.closest('.content');"+(phase==1?
                        "if(!window.__worldActivated){window.__worldActivated=true;const values="+JSON.toJson(WorldContentSmokeServer.location)+";for(const key of ['dimension','x','y','z']){const input=content.querySelector('[data-world-field='+key+']');input.value=values[key];input.dispatchEvent(new Event('change',{bubbles:true}));}content.querySelector('[data-world-consent]').checked=true;start.click();}":
                        "const stop=content.querySelector('[data-world-disable]');if(stop&&!window.__worldStopped){window.__worldStopped=true;stop.click();}"));
            }
        }
        if(WorldContentSmokeServer.verified&&phase==1&&!capturing){phase=2;capturing=true;Files.createDirectories(root());Files.writeString(root().resolve("active-dom.json"),JSON.toJson(probe));
            net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("active-ui.png"));capturing=false;after=ticks+10;}catch(Exception ex){throw new IllegalStateException(ex);}});
        }
        if(phase==2&&!capturing&&ticks>=after){h.close();WorldContentSmokeServer.interfaceClosed=true;phase=3;after=ticks+35;}
        if(phase==3&&ticks>=after&&!capturing){capturing=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("world-without-browser.png"));after=Integer.MAX_VALUE;capturing=false;}catch(Exception ex){throw new IllegalStateException(ex);}});}
        if(phase==3&&WorldContentSmokeServer.afterUiCloseVerified&&!capturing){phase=4;h.open();}
        if(WorldContentSmokeServer.disabledVerified){done=true;Files.writeString(root().resolve("final-dom.json"),JSON.toJson(probe));h.close();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_WORLD_CONTENT_GRAPHICAL_OK run={}",WorldContentSmokeServer.RUN);mc.stop();}
        if(WorldContentSmokeServer.failure!=null||ticks>12000){done=true;Files.createDirectories(root());Files.writeString(root().resolve("failure.json"),JSON.toJson(java.util.Map.of("phase",phase,"probe",probe==null?new JsonObject():probe,"code",WorldContentSmokeServer.failure==null?"TIMEOUT":WorldContentSmokeServer.failure)));h.close();mc.stop();}
    }
}
