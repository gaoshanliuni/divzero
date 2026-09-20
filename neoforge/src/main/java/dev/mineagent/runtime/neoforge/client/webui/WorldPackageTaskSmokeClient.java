package dev.mineagent.runtime.neoforge.client.webui;
import dev.mineagent.runtime.neoforge.task.WorldPackageTaskSmokeServer;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;

/** Actual trusted GUI fixture; raw model source is inspected before its external approval flag is written. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class WorldPackageTaskSmokeClient {
    private static final Gson JSON=new Gson();private static int ticks,phase,after;private static boolean opened,backup,done,capturing,interactionSent;private static JsonObject probe;
    public static void accept(JsonObject data){probe=data;}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve(WorldPackageTaskSmokeServer.directory());}
    private static String q(String value){return JSON.toJson(value);}
    private static void main(String code){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+code+"})();",h.browser().getURL(),0);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.worldPackageTaskSmoke")||done)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen s){backup=true;var f=s.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(s)).proceed(false,false);}
        if(mc.player!=null&&!opened){opened=true;host.open();}
        if(host.ready()&&UiClientSessions.current()!=null&&ticks%10==0){
            if(phase==0&&WorldPackageTaskSmokeServer.agentId!=null)main("""
                if(!document.querySelector('#world-task-prompt'))document.querySelector('#open-tasks').click();
                const a=document.querySelector('#world-task-agent');
                if(!RESUME&&!window.__worldTaskSent&&[...a.options].some(o=>o.value===AGENT)){
                  window.__worldTaskSent=true;a.value=AGENT;a.dispatchEvent(new Event('change',{bubbles:true}));const p=document.querySelector('#world-task-prompt');p.value=REQUEST;p.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#world-task-start').click();
                }
                if(RESUME&&!window.__worldWaitResumed){const b=document.querySelector('[data-world-task="'+TASK+'"] [data-task-control="resumeWorldWait"]');if(b&&!b.disabled){window.__worldWaitResumed=true;b.click();}}
                """.replace("AGENT",q(WorldPackageTaskSmokeServer.agentId)).replace("REQUEST",q(WorldPackageTaskSmokeServer.PROMPT)).replace("RESUME",Boolean.toString(WorldPackageTaskSmokeServer.resume())).replace("TASK",q(WorldPackageTaskSmokeServer.taskId)));
            if(WorldPackageTaskSmokeServer.packageId!=null&&(phase==0||phase==3))main("""
                if(!document.querySelector('#generation-prompt'))document.querySelector('#open-generation').click();
                const button=document.querySelector('[data-world-package="'+PKG+'"]');
                if(button&&!document.querySelector('[data-world-activate="'+PKG+'"]'))button.click();
                const start=document.querySelector('[data-world-activate="'+PKG+'"]');
                if(start&&APPROVED&&!window.__worldTaskApproved){
                  window.__worldTaskApproved=true;const c=start.parentElement;
                  for(const [key,value] of Object.entries(LOCATION)){const f=c.querySelector('[data-world-field="'+key+'"]');if(f){f.value=value;f.dispatchEvent(new Event('change',{bubbles:true}));}}
                  c.querySelector('[data-world-consent]').click();start.click();
                }
                if(DISABLE){const stop=document.querySelector('[data-world-disable]');if(stop&&!window.__worldTaskStopped){window.__worldTaskStopped=true;stop.click();}}
                """.replace("PKG",q(WorldPackageTaskSmokeServer.packageId)).replace("APPROVED",Boolean.toString(phase==0&&WorldPackageTaskSmokeServer.allowActivation)).replace("LOCATION",JSON.toJson(Map.of("x",WorldPackageTaskSmokeServer.location.x(),"y",WorldPackageTaskSmokeServer.location.y(),"z",WorldPackageTaskSmokeServer.location.z(),"dimension",WorldPackageTaskSmokeServer.location.dimension()))).replace("DISABLE",Boolean.toString(phase==3)));
            main("window.mineagentQuery({request:JSON.stringify({channel:'worldPackageTaskProbe',text:document.body.innerText.slice(-10000),nativeConsent:!!document.querySelector('[data-world-consent]')?.checked}),persistent:false,onSuccess(){},onFailure(){}});");
        }
        if(phase==0&&WorldPackageTaskSmokeServer.completed&&!capturing&&objectsRendered()){phase=1;capturing=true;Files.writeString(root().resolve("completed-dom.json"),JSON.toJson(probe));net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("completed.png"));capturing=false;after=ticks+20;}catch(Exception e){throw new IllegalStateException(e);}});}
        if(phase==1&&!capturing&&ticks>=after){host.close();WorldPackageTaskSmokeServer.uiClosed=true;phase=2;after=ticks+30;}
        if(phase==2&&ticks>=after&&!capturing){capturing=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("world-without-browser.png"));after=Integer.MAX_VALUE;capturing=false;}catch(Exception e){throw new IllegalStateException(e);}});}
        if(phase==2&&WorldPackageTaskSmokeServer.independent&&!capturing){if(WorldPackageTaskSmokeServer.objectsMode()){if(!interactionSent){interactionSent=true;var e=mc.level.getEntity(WorldPackageTaskSmokeServer.objectIds.get("base"));if(e==null)throw new IllegalStateException("GENERATED_BASE_NOT_RENDERED");mc.gameMode.interact(mc.player,e,new net.minecraft.world.phys.EntityHitResult(e,e.position().add(0,1,0)),net.minecraft.world.InteractionHand.MAIN_HAND);}if(!WorldPackageTaskSmokeServer.objectInteractionVerified)return;}phase=3;host.open();}
        if(WorldPackageTaskSmokeServer.done){done=true;Files.writeString(root().resolve("final-dom.json"),JSON.toJson(probe));host.close();mc.stop();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_WORLD_PACKAGE_TASK_OK run={}",WorldPackageTaskSmokeServer.RUN);}
        if(WorldPackageTaskSmokeServer.failure!=null||ticks>30000){done=true;Files.createDirectories(root());Files.writeString(root().resolve("client-failure.json"),JSON.toJson(Map.of("phase",phase,"error",WorldPackageTaskSmokeServer.failure==null?"TIMEOUT":WorldPackageTaskSmokeServer.failure,"probe",probe==null?new JsonObject():probe)));host.close();mc.stop();throw new IllegalStateException("WORLD_PACKAGE_TASK_SMOKE_FAILED");}
    }
    private static boolean objectsRendered(){if(!WorldPackageTaskSmokeServer.objectsMode())return true;if(WorldPackageTaskSmokeServer.objectIds.size()!=3)return false;return WorldPackageTaskSmokeServer.objectIds.values().stream().allMatch(id->dev.mineagent.runtime.neoforge.client.objects.RuntimeObjectRenderer.drawn.getOrDefault(id,0)>10);}
}
