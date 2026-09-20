package dev.mineagent.runtime.neoforge.client.webui;
import dev.mineagent.runtime.neoforge.content.WorldRestoreSmokeServer;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class WorldRestoreSmokeClient {
    private static final Gson JSON=new Gson();private static boolean opened,backup,capturing,captured,done;private static int ticks,after;private static JsonObject probe;
    public static void accept(JsonObject value){if(Boolean.getBoolean("mineagent.worldRestoreSmoke"))probe=value;}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve(WorldRestoreSmokeServer.directory());}
    private static String q(String s){return JSON.toJson(s);}
    private static void main(String code){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+code+"})();",h.browser().getURL(),0);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post e)throws Exception{
        if(!Boolean.getBoolean("mineagent.worldRestoreSmoke")||done)return;var mc=Minecraft.getInstance();var h=WebGuiHostAdapter.INSTANCE;ticks++;
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen s){backup=true;var f=s.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(s)).proceed(false,false);}
        boolean needsUi=WorldRestoreSmokeServer.STAGE.equals("seed")||WorldRestoreSmokeServer.revokeRequested;
        if(mc.player!=null&&!opened&&needsUi){opened=true;mc.options.pauseOnLostFocus=false;h.open();}
        if(h.ready()&&UiClientSessions.current()!=null&&ticks%20==0){
            main("window.mineagentQuery({request:JSON.stringify({channel:'worldRestoreProbe',status:document.querySelector('#status').textContent,worldText:[...document.querySelectorAll('[data-world-activate]')].map(b=>b.closest('.content').textContent).join('\\n')}),persistent:false,onSuccess(){},onFailure(){}});");
            if(WorldRestoreSmokeServer.STAGE.equals("seed")&&WorldRestoreSmokeServer.agentId!=null&&System.getProperty("mineagent.worldRestoreResume","").isBlank()){
                main("if(!document.querySelector('#generation-prompt'))document.querySelector('#open-generation').click();const agent=document.querySelector('#generation-agent');if(![...agent.options].some(o=>o.value==="+q(WorldRestoreSmokeServer.agentId)+"))return;if(!window.__restoreGenerated){window.__restoreGenerated=true;agent.value="+q(WorldRestoreSmokeServer.agentId)+";const scope=document.querySelector('#generation-purpose');scope.value='WORLD_CONTENT';scope.dispatchEvent(new Event('change',{bubbles:true}));const text=document.querySelector('#generation-prompt');text.value="+q(WorldRestoreSmokeServer.PROMPT)+";text.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#generation-submit').click();}");
            }
            if(WorldRestoreSmokeServer.packageId!=null&&!WorldRestoreSmokeServer.finished){
                String pkg=WorldRestoreSmokeServer.packageId;
                main("if(!document.querySelector('#generation-prompt'))document.querySelector('#open-generation').click();const button=document.querySelector('[data-world-package=\""+pkg+"\"]');if(!button)return;button.click();const start=document.querySelector('[data-world-activate=\""+pkg+"\"]');if(!start)return;const content=start.closest('.content');"+
                        (WorldRestoreSmokeServer.STAGE.equals("seed")?"if(!window.__restoreActivated){const auto=content.querySelector('[data-world-auto-restore]');if(auto.disabled)return;window.__restoreActivated=true;const location="+JSON.toJson(WorldRestoreSmokeServer.location)+";for(const key of ['dimension','x','y','z']){const input=content.querySelector('[data-world-field='+key+']');input.value=location[key];input.dispatchEvent(new Event('change',{bubbles:true}));}content.querySelector('[data-world-consent]').checked=true;auto.checked=true;start.click();}":
                        "const revoke=content.querySelector('[data-world-restore-off=\""+WorldRestoreSmokeServer.activationToRevoke()+"\"]');if(revoke&&!window.__restoreRevoked){window.__restoreRevoked=true;revoke.click();}"));
            }
        }
        if(WorldRestoreSmokeServer.finished&&!capturing){capturing=true;after=ticks+15;}
        if(capturing&&!captured&&ticks==after){Files.createDirectories(root());Files.writeString(root().resolve("client-dom.json"),JSON.toJson(probe));net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("stage.png"));captured=true;}catch(Exception ex){throw new IllegalStateException(ex);}});}
        if(captured){done=true;h.close();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_WORLD_RESTORE_STAGE_OK stage={} run={}",WorldRestoreSmokeServer.STAGE,WorldRestoreSmokeServer.RUN);mc.stop();}
        if(WorldRestoreSmokeServer.failure!=null||ticks>12000){done=true;Files.createDirectories(root());Files.writeString(root().resolve("client-failure.json"),JSON.toJson(java.util.Map.of("stage",WorldRestoreSmokeServer.STAGE,"probe",probe==null?new JsonObject():probe,"code",WorldRestoreSmokeServer.failure==null?"TIMEOUT":WorldRestoreSmokeServer.failure)));h.close();mc.stop();}
    }
}
