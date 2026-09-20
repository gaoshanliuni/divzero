package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.neoforge.ui.PageOnlyAgentSmokeServer;
import dev.mineagent.runtime.api.ui.UiProtocol.ActorKind;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Trusted approval/setup driver. All generated-form filling and submit actions are chosen by the real model. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class PageOnlyAgentSmokeClient {
    private static final Gson JSON=new Gson();private static boolean opened,backup,busy,ended,probed;private static String view,failure;private static int ticks,phase;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.pageOnlyAgentSmoke")||ended)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen s){backup=true;var f=s.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(s)).proceed(false,false);}
        if(mc.player!=null&&!opened){opened=true;host.open();}
        if(host.ready()&&UiClientSessions.current()!=null&&ticks%10==0&&phase==0){main("if(!document.querySelector('#generation-prompt'))document.querySelector('#open-generation').click();const card=[...document.querySelectorAll('.generation-job:not(.patch-job)')].find(n=>n.dataset.packageId==="+quote(PageOnlyAgentSmokeServer.packageId().toString())+"&&Number(n.dataset.packageRevision)==="+PageOnlyAgentSmokeServer.revision()+");if(card&&!window.__pageAgentOpened){const button=[...card.querySelectorAll('button')].find(b=>b.textContent==='打开独立预览');if(button){window.__pageAgentOpened=true;button.click();}}");}
        if(phase==0&&host.ready()&&!busy){view=host.loadedPreviewViews().stream().filter(v->{var p=host.viewPackage(v);return p!=null&&p.packageId().equals(PageOnlyAgentSmokeServer.packageId())&&p.revision()==PageOnlyAgentSmokeServer.revision();}).findFirst().orElse(null);
            if(view!=null){busy=true;PackagePageAgent.inspectManagedView(view).whenComplete((value,error)->mc.execute(()->{try{
                if(error!=null)throw new IllegalStateException(error);if(!JsonParser.parseString(value).getAsJsonObject().get("visibleText").getAsString().contains("巡逻交接便笺")){busy=false;return;}
                if(PageOnlyAgentSmokeServer.restoreOnly()&&!JsonParser.parseString(value).getAsJsonObject().get("visibleText").getAsString().contains(PageOnlyAgentSmokeServer.NOTE)){busy=false;return;}
                Files.createDirectories(root());Files.writeString(root().resolve("before-dom.json"),value);phase=1;busy=false;
                if(!PageOnlyAgentSmokeServer.restoreOnly())main("document.querySelector('[data-view-id=\""+view+"\"] [data-action=\"open-page-delegation\"]').click();");
            }catch(Exception e){failure=e.toString();}}));}
        }
        if(!PageOnlyAgentSmokeServer.restoreOnly()&&phase==1&&ticks%10==0){main("const card=document.querySelector('[data-delegation-view=\""+view+"\"]');if(!card||window.__pageAgentApproved)return;window.__pageAgentApproved=true;const agent=card.querySelector('[data-field=agent]');agent.value="+quote(PageOnlyAgentSmokeServer.agentId)+";agent.dispatchEvent(new Event('change',{bubbles:true}));const goal=card.querySelector('[data-field=goal]');goal.value="+quote(PageOnlyAgentSmokeServer.GOAL)+";goal.dispatchEvent(new Event('input',{bubbles:true}));const expected=card.querySelector('[data-field=expectedTitle]');expected.value="+quote(PageOnlyAgentSmokeServer.EXPECTED)+";expected.dispatchEvent(new Event('input',{bubbles:true}));card.querySelector('[data-field=consent]').click();card.querySelector('[data-action=delegate]').click();");}
        var live=view==null?null:PackageContentClient.session(view);
        if(!PageOnlyAgentSmokeServer.restoreOnly()&&!PageOnlyAgentSmokeServer.ALREADY&&!probed&&live!=null&&live.binding().actorKind()==ActorKind.AGENT){probed=true;frame("const p=document.createElement('p');p.id='page-world-denial-probe';document.body.append(p);window.mineagentContentQuery({request:JSON.stringify({action:'scoreview.patch',operationId:crypto.randomUUID(),arguments:{expectedViewRevision:'1',patch:JSON.stringify({title:'FORBIDDEN_PAGE_WRITE'})}}),persistent:false,onSuccess(value){const r=JSON.parse(value);p.textContent='PAGE_WORLD_WRITE_'+r.code;},onFailure(code,message){p.textContent='PAGE_WORLD_PROBE_FAILED '+message;}});");}
        if(PageOnlyAgentSmokeServer.restoreOnly()&&PageOnlyAgentSmokeServer.verified&&phase==1&&!probed&&!busy){
            probed=true;frame("const marker=document.createElement('p');marker.id='cas-probe';document.body.append(marker);(async()=>{try{const before=await window.mineagentState.get('handoff');try{await window.mineagentState.put('handoff',0,{entries:[],filterArea:'全部'});marker.textContent='CAS_UNEXPECTED_WRITE';return;}catch(e){if(e.message!=='UI_STATE_CONFLICT')throw e;}const after=await window.mineagentState.get('handoff');marker.textContent=before.revision===after.revision&&JSON.stringify(before.value)===JSON.stringify(after.value)?'STALE_CAS_REJECTED_DATA_UNCHANGED':'CAS_CHANGED_DATA';}catch(e){marker.textContent='CAS_PROBE_FAILED '+e.message;}})();");
        }
        if(PageOnlyAgentSmokeServer.verified&&phase==1&&live==null&&!busy){phase=2;busy=true;
            // Evidence-only viewport movement after the Agent has stopped; not counted among model actions.
            frame("window.scrollTo(0,0);");main("document.querySelector('[data-view-id=\""+view+"\"]').dispatchEvent(new PointerEvent('pointerdown',{bubbles:true}));");
            CompletableFuture.delayedExecutor(PageOnlyAgentSmokeServer.restoreOnly()?1000:250,TimeUnit.MILLISECONDS).execute(()->mc.execute(()->finishObservation()));
        }
        if(phase==3&&!busy){busy=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("render.png"));phase=4;}catch(Exception e){failure=e.toString();}});}
        if(phase==4){Files.writeString(root().resolve("client-result.json"),JSON.toJson(Map.of("delegationApprovedBy","TRUSTED_GUI_FIXTURE","generatedFormActions",PageOnlyAgentSmokeServer.restoreOnly()?"NO_MODEL_REOPEN":"REAL_MODEL","worldWriteDenied",probed,"negativeInitialCondition",PageOnlyAgentSmokeServer.ALREADY,"businessVerified",false)));ended=true;host.close();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_PAGE_ONLY_AGENT_CLIENT_OK negative={}",PageOnlyAgentSmokeServer.ALREADY);mc.stop();}
        if(failure!=null||ticks>7200){Files.createDirectories(root());Files.writeString(root().resolve("failure.json"),JSON.toJson(Map.of("phase",phase,"error",failure==null?"TIMEOUT":failure,"screen",mc.screen==null?"none":mc.screen.getClass().getName())));throw new IllegalStateException("PAGE_ONLY_AGENT_SMOKE_FAILED: "+failure);}
    }
    private static void finishObservation(){PackagePageAgent.inspectManagedView(view).whenComplete((value,error)->Minecraft.getInstance().execute(()->{try{
        if(error!=null)throw new IllegalStateException(error);String text=JsonParser.parseString(value).getAsJsonObject().get("visibleText").getAsString();
        if(!PageOnlyAgentSmokeServer.ALREADY&&(!text.contains(PageOnlyAgentSmokeServer.WHO)||!text.contains(PageOnlyAgentSmokeServer.AREA)||!text.contains(PageOnlyAgentSmokeServer.NOTE)||(!PageOnlyAgentSmokeServer.restoreOnly()&&!(text.contains("PAGE_WORLD_WRITE_PERMISSION_DENIED")||text.contains("PAGE_WORLD_WRITE_PREVIEW_READ_ONLY")))))throw new IllegalStateException("PAGE_RECORD_OR_WORLD_DENIAL_MISSING");
        if(PageOnlyAgentSmokeServer.restoreOnly()&&!text.contains("STALE_CAS_REJECTED_DATA_UNCHANGED"))throw new IllegalStateException("NATIVE_CAS_REPLAY_GUARD_FAILED");
        Files.writeString(root().resolve("after-dom.json"),value);String document=JsonParser.parseString(value).getAsJsonObject().get("documentId").getAsString();
        // Local-only negative probe: submit event must fire, but CSP must prevent even POST navigation to this mount.
        frame("const form=document.createElement('form');form.method='post';form.action=location.href;form.addEventListener('submit',()=>{const p=document.createElement('p');p.textContent='FORM_SUBMIT_EVENT_OBSERVED';document.body.append(p);});document.body.append(form);form.requestSubmit();");
        CompletableFuture.delayedExecutor(250,TimeUnit.MILLISECONDS).execute(()->Minecraft.getInstance().execute(()->verifyFormPolicy(document)));
    }catch(Exception e){failure=e.toString();}}));}
    private static void verifyFormPolicy(String document){PackagePageAgent.inspectManagedView(view).whenComplete((value,error)->Minecraft.getInstance().execute(()->{try{
        if(error!=null)throw new IllegalStateException(error);var dom=JsonParser.parseString(value).getAsJsonObject();
        if(!dom.get("documentId").getAsString().equals(document)||!dom.get("visibleText").getAsString().contains("FORM_SUBMIT_EVENT_OBSERVED"))throw new IllegalStateException("FORM_POLICY_NAVIGATED_OR_EVENT_BLOCKED");
        Files.writeString(root().resolve("form-policy-dom.json"),value);PackagePageAgent.captureManagedView(view).whenComplete((shot,err)->Minecraft.getInstance().execute(()->{try{if(err!=null)throw new IllegalStateException(err);Files.write(root().resolve("view.png"),shot.png());phase=3;busy=false;}catch(Exception e){failure=e.toString();}}));
    }catch(Exception e){failure=e.toString();}}));}
    private static String quote(String s){return JSON.toJson(s);}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve(PageOnlyAgentSmokeServer.directory());}
    private static void main(String script){var host=WebGuiHostAdapter.INSTANCE;host.browser().executeJavaScript("(()=>{"+script+"})();",host.browser().getURL(),0);}
    private static void frame(String script){var host=WebGuiHostAdapter.INSTANCE;String url=host.packageUrl(view);for(long id:host.browser().getFrameIdentifiers()){var f=host.browser().getFrame(id);if(f!=null&&!f.isMain()&&url.equals(f.getURL())){f.executeJavaScript(script,url,0);return;}}throw new IllegalStateException("PAGE_AGENT_FRAME_MISSING");}
}
