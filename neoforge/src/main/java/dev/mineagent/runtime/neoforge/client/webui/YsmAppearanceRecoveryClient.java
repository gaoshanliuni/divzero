package dev.mineagent.runtime.neoforge.client.webui;

import com.google.gson.*;
import dev.mineagent.runtime.neoforge.client.ysm.YsmRenderObserver;
import dev.mineagent.runtime.neoforge.ui.YsmAppearanceRecoveryServer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;

/** Three distinct JVMs in one isolated integrated profile. UI evidence comes from actual Chromium controls. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class YsmAppearanceRecoveryClient {
    private static int ticks,measureAt,browserClosedAt=-1,finishedAt=-1;
    private static boolean opened,backupAccepted,capturing,captured,measuring,stopped,disconnectRequested;
    private static JsonObject probe;
    static void probe(JsonObject value){probe=value.deepCopy();}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!YsmAppearanceRecoveryServer.enabled()||stopped)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;
        Path root=mc.gameDirectory.toPath().resolve("appearance-recovery-evidence");Files.createDirectories(root);String stage=YsmAppearanceRecoveryServer.stage();
        if(!backupAccepted&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen backup){
            backupAccepted=true;var field=backup.getClass().getDeclaredField("onProceed");field.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)field.get(backup)).proceed(false,false);
        }
        if(mc.player!=null&&!opened){opened=true;host.open();}
        if(YsmAppearanceRecoveryServer.finished){
            if(finishedAt<0)finishedAt=ticks;
            if(!disconnectRequested&&(stage.equals("prepare")||ticks-finishedAt>=20)){disconnectRequested=true;mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Appearance recovery fixture stage complete"));}
            if(YsmRecoveryStopPolicy.mayStop(true,mc.getConnection()!=null,mc.level!=null)){stopped=true;mc.stop();}return;
        }
        if(captured&&YsmRecoveryStopPolicy.mayStage(ticks,browserClosedAt))YsmAppearanceRecoveryServer.finishRequested=true;
        var plan=YsmAppearanceRecoveryServer.clientPlan;
        if(host.ready()&&UiClientSessions.current()!=null&&!plan.isEmpty()&&ticks%10==0&&!captured){
            String js=SCRIPT.replace("PLAN",new Gson().toJson(plan)).replace("STAGE",new Gson().toJson(stage));
            host.browser().executeJavaScript("(()=>{"+js+"})();",host.browser().getURL(),0);
        }
        if(probe!=null&&!probe.get("error").getAsString().isEmpty()){
            Files.writeString(root.resolve(stage+"-gui-failure.json"),probe.toString());stopped=true;host.close();mc.stop();throw new IllegalStateException("RECOVERY_UI_FAILURE: "+probe.get("error").getAsString());
        }
        if(probe!=null&&probe.get("done").getAsBoolean()&&!capturing&&!captured){
            var agent=UUID.fromString(plan.get("agent"));
            if(!measuring){YsmRenderObserver.expect(agent);measureAt=ticks;measuring=true;return;}
            var render=YsmRenderObserver.snapshot();var nativeView=YsmRenderObserver.inspectExpectedClientState();
            if(ticks-measureAt<40||nativeView.filter(s->s.renderReady()&&s.modelId().equals("default")&&s.textureId().equals("blue")).isEmpty()||render.ysmCalls()<10)return;
            if(render.vanillaCalls()!=0||render.duplicateDraws()!=0)throw new IllegalStateException("RECOVERY_RENDER_PARITY");
            if(!(mc.screen instanceof WebGuiInteractionScreen))throw new IllegalStateException("RECOVERY_UI_NOT_VISIBLE");
            Files.writeString(root.resolve(stage+"-gui.json"),probe.toString());
            Files.writeString(root.resolve(stage+"-render.json"),new Gson().toJson(Map.of("frames",render.uniqueFrames(),"ysmDraws",render.ysmCalls(),"vanillaDraws",render.vanillaCalls(),"duplicateDraws",render.duplicateDraws(),"nativeView",nativeView.orElseThrow(),"pid",ProcessHandle.current().pid())));
            capturing=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root.resolve(stage+"-gui.png"));host.close();browserClosedAt=ticks;captured=true;}catch(Exception failure){throw new IllegalStateException(failure);}});
        }
        if(ticks>5000){Files.writeString(root.resolve(stage+"-timeout.json"),new Gson().toJson(Map.of("plan",plan,"probe",probe==null?new JsonObject():probe,"host",host.diagnostic())));stopped=true;host.close();mc.stop();throw new IllegalStateException("RECOVERY_UI_TIMEOUT");}
    }

    private static final String SCRIPT="""
        const plan=PLAN,stage=STAGE,s=window.__appearanceRecovery??={sent:false,staleSent:false,done:false,error:''};
        const card=id=>document.querySelector('[data-decision-id="'+id+'"]');
        const effect=id=>card(id)?.querySelector('[data-domain-effect]');
        try{
          const entry=document.querySelector('#decision-entry');
          const keys=stage==='prepare'?['initial']:['initial','unknown','outbox','pending','stale'];
          for(const key of keys)if(!card(plan[key])){
            if(![...entry.options].some(o=>o.value===plan[key]))return;
            entry.value=plan[key];entry.dispatchEvent(new Event('change',{bubbles:true}));
          }
          if(stage==='prepare'){
            if(!s.sent){const c=card(plan.initial);c.querySelector('[data-option-id="typed"]').click();c.querySelector('[data-ai-id="decision-submit"]').click();s.sent=true;}
            s.done=effect(plan.initial)?.dataset.domainEffect==='APPLIED';
          }else{
            if(stage==='resume'){
              if(!s.sent){
                document.querySelector('#open-chat').click();const agent=document.querySelector('#chat-agent');agent.value=plan.agent;agent.dispatchEvent(new Event('change',{bubbles:true}));
                const target=document.querySelector('#chat-decision');if(![...target.options].some(o=>o.value===plan.pending))return;target.value=plan.pending;
                const text=document.querySelector('#chat-draft');text.value='第1个，但 texture=blue animation=idle';text.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text.value}));
                [...text.parentElement.querySelectorAll('button')].find(b=>b.textContent==='发送私密消息').click();s.sent=true;
              }
              if(effect(plan.pending)?.dataset.domainEffect==='APPLIED'&&!s.staleSent){const c=card(plan.stale);c.querySelector('[data-option-id="typed"]').click();c.querySelector('[data-ai-id="decision-submit"]').click();s.staleSent=true;}
            }
            const expected={initial:'APPLIED',pending:'APPLIED',stale:'FAILED',unknown:'INTERRUPTED',outbox:'APPLIED'};
            s.done=Object.entries(expected).every(([key,state])=>effect(plan[key])?.dataset.domainEffect===state)
              &&effect(plan.stale)?.textContent.includes('STALE_REVISION')&&effect(plan.unknown)?.textContent.includes('NATIVE_OUTCOME_UNKNOWN')&&effect(plan.unknown)?.textContent.includes('revision 未确认');
          }
          s.cards=Object.fromEntries(keys.map(key=>[key,{id:plan[key],effect:effect(plan[key])?.dataset.domainEffect??'',text:effect(plan[key])?.textContent??'',answer:card(plan[key])?.querySelector('textarea')?.value??''}]));
        }catch(e){s.error=String(e);}
        window.mineagentQuery({request:JSON.stringify({channel:'appearanceRecoveryProbe',...s}),persistent:false,onSuccess(){},onFailure(){}});
        """;
}
