package dev.mineagent.runtime.neoforge.client.webui;

import dev.mineagent.runtime.neoforge.task.WorldPatchSmokeServer;
import dev.mineagent.runtime.neoforge.client.objects.RuntimeObjectRenderer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;

/** Trusted GUI driver, not a model operator. External reviewed SHA flags gate both separate confirmations. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class WorldPatchSmokeClient {
    private static int ticks,phase,after;private static boolean opened,backup,capturing,interactionSent,ended;private static volatile boolean imageDone;
    private static boolean reviewStarted;private static volatile boolean reviewReady;
    private static final com.google.gson.Gson JSON=new com.google.gson.Gson();
    public static void accept(com.google.gson.JsonObject value){try{reviewReady=value.has("sourceReady")&&value.get("sourceReady").getAsBoolean();Files.writeString(root().resolve("gui.json"),JSON.toJson(value));}catch(Exception e){throw new IllegalStateException(e);}}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve(WorldPatchSmokeServer.directory());}
    private static String q(String value){return JSON.toJson(value);}
    private static void main(String code){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+code+"})();",h.browser().getURL(),0);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.worldPatchSmoke")||ended)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;Files.createDirectories(root());
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen s){backup=true;var f=s.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(s)).proceed(false,false);}
        if(mc.player!=null&&!opened){opened=true;host.open();}
        if(WorldPatchSmokeServer.failure!=null||ticks>24000){ended=true;host.close();mc.stop();throw new IllegalStateException("WORLD_PATCH_SMOKE_FAILED: "+WorldPatchSmokeServer.failure);}
        if(phase==6){if(ticks>=after){ended=true;dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_WORLD_PATCH_OK run={}",WorldPatchSmokeServer.RUN);mc.stop();}return;}
        if(WorldPatchSmokeServer.verifyOnly()){
            if(WorldPatchSmokeServer.done&&WorldPatchSmokeServer.objects.size()==3&&WorldPatchSmokeServer.objects.values().stream().allMatch(id->RuntimeObjectRenderer.drawn.getOrDefault(id,0)>10)){
                if(!capturing)capture("restart-native.png");if(imageDone){Files.writeString(root().resolve("client-result.json"),JSON.toJson(Map.of("draws",RuntimeObjectRenderer.drawn,"status","REPAIRED_WORLD_RESTART_DISABLED_VERIFIED","providerCalls",0,"fullV1",false)));phase=6;host.close();mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("World patch restart verified"));after=ticks+20;}
            }return;
        }
        if(host.ready()&&UiClientSessions.current()!=null&&WorldPatchSmokeServer.packageId!=null&&ticks%10==0){
            if(phase==0)main("""
                if(!document.querySelector('#generation-prompt'))document.querySelector('#open-generation').click();
                if(!HAS_JOB&&!window.__worldPatchSent){const open=document.querySelector('[data-world-patch-package="'+PKG+'"]');if(!open)return;open.click();const input=document.querySelector('#world-patch-prompt');input.value=PROMPT;input.dispatchEvent(new Event('input',{bubbles:true}));window.__worldPatchSent=true;document.querySelector('#world-patch-submit').click();}
                if(OP){const inspect=document.querySelector('[data-world-patch-review="'+OP+'"]');if(inspect&&!document.querySelector('[data-world-patch-apply="'+OP+'"]'))inspect.click();
                  const select=document.querySelector('select[aria-label="世界候选资源"]');if(select&&[...select.options].some(o=>o.value==='server/main.js')&&select.value!=='server/main.js'){select.value='server/main.js';select.dispatchEvent(new Event('change',{bubbles:true}));}
                  const apply=document.querySelector('[data-world-patch-apply="'+OP+'"]');if(APPLY&&apply&&!apply.disabled&&!window.__worldPatchApplied){window.__worldPatchApplied=true;const c=apply.parentElement;c.querySelector('[data-world-patch-consent]').click();apply.click();}
                }
                """.replace("HAS_JOB",Boolean.toString(WorldPatchSmokeServer.patchOperation!=null)).replace("PKG",q(WorldPatchSmokeServer.packageId)).replace("PROMPT",q(WorldPatchSmokeServer.PROMPT)).replace("OP",q(WorldPatchSmokeServer.patchOperation)).replace("APPLY",Boolean.toString(WorldPatchSmokeServer.allowApply)));
            if(WorldPatchSmokeServer.allowNative||phase==4)main("""
                if(!document.querySelector('#generation-prompt'))document.querySelector('#open-generation').click();
                const open=document.querySelector('[data-world-package="'+PKG+'"]');if(open&&!document.querySelector('[data-world-activate="'+PKG+'"]'))open.click();
                const start=document.querySelector('[data-world-activate="'+PKG+'"]');if(NATIVE&&start&&!window.__worldPatchNative){const c=start.parentElement;for(const [key,value]of Object.entries(LOCATION)){const f=c.querySelector('[data-world-field="'+key+'"]');if(f){f.value=value;f.dispatchEvent(new Event('change',{bubbles:true}));}}window.__worldPatchNative=true;c.querySelector('[data-world-consent]').click();start.click();}
                if(STOP&&!window.__worldPatchStopped){const button=document.querySelector('[data-world-disable="'+ACTIVATION+'"]');if(button){window.__worldPatchStopped=true;button.click();}}
                """.replace("PKG",q(WorldPatchSmokeServer.packageId)).replace("NATIVE",Boolean.toString(WorldPatchSmokeServer.allowNative)).replace("LOCATION",JSON.toJson(Map.of("x",WorldPatchSmokeServer.location.x(),"y",WorldPatchSmokeServer.location.y(),"z",WorldPatchSmokeServer.location.z(),"dimension",WorldPatchSmokeServer.location.dimension()))).replace("STOP",Boolean.toString(phase==4)).replace("ACTIVATION",q(String.valueOf(WorldPatchSmokeServer.activationId))));
            main("window.mineagentQuery({request:JSON.stringify({channel:'worldPatchProbe',text:document.body.innerText.slice(-20000),sourceReady:!!document.querySelector('[data-world-patch-source]')?.textContent.includes('1.4'),worldPatchConsent:!!document.querySelector('[data-world-patch-consent]')?.checked,nativeConsent:!!document.querySelector('[data-world-consent]')?.checked}),persistent:false,onSuccess(){},onFailure(){}});");
        }
        if(phase==0&&reviewReady&&!reviewStarted){reviewStarted=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("candidate-review.png"));}catch(Exception e){throw new IllegalStateException(e);}});}
        if(phase==0&&WorldPatchSmokeServer.active&&WorldPatchSmokeServer.objects.values().stream().allMatch(id->RuntimeObjectRenderer.drawn.getOrDefault(id,0)>10)){if(!capturing)capture("active-gui.png");if(imageDone){capturing=false;imageDone=false;host.close();WorldPatchSmokeServer.closedGui=true;phase=1;after=ticks+20;}}
        if(phase==1&&ticks>=after){if(!capturing)capture("native-world.png");if(imageDone){capturing=false;imageDone=false;phase=2;}}
        if(phase==2&&WorldPatchSmokeServer.independent){if(!interactionSent){interactionSent=true;var entity=mc.level.getEntity(WorldPatchSmokeServer.objects.get("base"));mc.gameMode.interact(mc.player,entity,new net.minecraft.world.phys.EntityHitResult(entity,entity.position().add(0,1,0)),net.minecraft.world.InteractionHand.MAIN_HAND);}if(WorldPatchSmokeServer.interacted){phase=4;host.open();}}
        if(WorldPatchSmokeServer.done&&phase!=6){Files.writeString(root().resolve("client-result.json"),JSON.toJson(Map.of("draws",RuntimeObjectRenderer.drawn,"status","REPAIRED_GENERATED_WORLD_NATIVE_VERIFIED","newPackageGenerated",false,"fullV1",false)));phase=6;host.close();mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("World patch fixture complete"));after=ticks+20;}
    }
    private static void capture(String file){capturing=true;net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve(file));imageDone=true;}catch(Exception e){throw new IllegalStateException(e);}});}
}
