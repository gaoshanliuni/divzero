package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.neoforge.ui.ScoreUiSmokeServer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
/** Continues the real interrupted-Agent fixture through read-only restoration and an explicit PLAYER save. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class TakeoverSmokeClient {
    private static String oldView,newView,oldDocument;private static int ticks,stage;private static boolean observing,screenShot,ended,opened,backup;private static volatile boolean captured;
    private static boolean resumeMode(){return Boolean.getBoolean("mineagent.takeoverResumeSmoke");}
    private static String evidenceDirectory(){return resumeMode()?dev.mineagent.runtime.neoforge.ui.TakeoverResumeSmokeServer.evidenceDirectory():ScoreUiSmokeServer.evidenceDirectory();}
    private static boolean readOnlyResume(){return resumeMode()&&dev.mineagent.runtime.neoforge.ui.TakeoverResumeSmokeServer.readOnly();}
    private static String expectedDraft(){return System.getProperty("mineagent.takeoverResumeDraft","人工草稿");}
    static void start(String view,String observation){oldView=view;oldDocument=JsonParser.parseString(observation).getAsJsonObject().get("documentId").getAsString();
        var host=WebGuiHostAdapter.INSTANCE;host.browser().executeJavaScript("document.querySelector('[data-view-id=\""+view+"\"] [data-action=\"takeover\"]').click();",host.browser().getURL(),0);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if((!ScoreUiSmokeServer.takeoverMode()&&!resumeMode())||ended)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;
        if(resumeMode()){
            if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen s){backup=true;var f=s.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(s)).proceed(false,false);}
            if(mc.player!=null&&!opened){opened=true;host.open();oldView="";oldDocument=System.getProperty("mineagent.takeoverResumeOldDocument","previous-process");if(readOnlyResume()&&oldDocument.isBlank())throw new IllegalArgumentException("PREVIOUS_DOCUMENT_REQUIRED");}
            if(host.ready()&&UiClientSessions.current()!=null&&dev.mineagent.runtime.neoforge.ui.TakeoverResumeSmokeServer.prepared&&newView==null){
                host.browser().executeJavaScript("""
                    (()=>{if(!document.querySelector('#generation-prompt'))document.querySelector('#open-generation').click();
                    const button=document.querySelector('[data-restore-view-id="TARGET"]');if(button&&!window.__resumeClicked){window.__resumeClicked=true;button.click();}})();
                    """.replace("TARGET",System.getProperty("mineagent.takeoverResumeView")),host.browser().getURL(),0);
            }
        }
        if(oldView==null||!host.ready())return;ticks++;
        var root=mc.gameDirectory.toPath().resolve(evidenceDirectory());Files.createDirectories(root);
        if(stage==0&&ticks%10==0){
            host.browser().executeJavaScript("""
                (()=>{const node=document.querySelector('[data-takeover-status="READ_ONLY_READY"]');if(!node)return;
                window.mineagentQuery({request:JSON.stringify({channel:'takeoverProbe',viewId:node.dataset.viewId}),persistent:false,onSuccess(){},onFailure(){}});})();
                """,host.browser().getURL(),0);
        }
        if(newView!=null&&stage==0&&!observing){
            observing=true;PackagePageAgent.inspectManagedView(newView).whenComplete((value,error)->mc.execute(()->{try{
                if(error!=null)throw new IllegalStateException(error);var dom=JsonParser.parseString(value).getAsJsonObject();
                if(dom.get("documentId").getAsString().equals(oldDocument)||newView.equals(oldView)||!hasDraft(dom))throw new IllegalStateException("TAKEOVER_NEW_DOCUMENT_OR_DRAFT");
                Files.writeString(root.resolve("restored-dom.json"),value);
                frameScript(newView,"""
                    const marker=document.createElement('p');marker.id='restore-permission-probe';document.body.append(marker);
                    window.mineagentUi.read().then(state=>window.mineagentUi.patch(state.viewRevision,{title:'MUST_NOT_SAVE'},crypto.randomUUID())).then(()=>marker.textContent='RESTORE_UNEXPECTED_WRITE').catch(e=>marker.textContent='RESTORE_WRITE_REJECTED '+e.message);
                    """);stage=1;observing=false;
            }catch(Exception failure){throw new IllegalStateException(failure);}}));
        }
        if(stage==1&&ticks%20==0&&!observing){observing=true;PackagePageAgent.inspectManagedView(newView).whenComplete((value,error)->mc.execute(()->{try{
            observing=false;if(error!=null)throw new IllegalStateException(error);var dom=JsonParser.parseString(value).getAsJsonObject();String text=dom.get("visibleText").getAsString();
            if(text.contains("RESTORE_UNEXPECTED_WRITE"))throw new IllegalStateException("RESTORE_WRITE_ALLOWED");
            if(!text.contains("RESTORE_WRITE_REJECTED"))return;if(!hasDraft(dom))throw new IllegalStateException("RESTORE_DRAFT_LOST");
            Files.writeString(root.resolve("read-only-dom.json"),value);
            if(readOnlyResume()){
                if(!text.contains("PERMISSION_DENIED")&&!text.contains("PREVIEW_READ_ONLY"))throw new IllegalStateException("RECOVERY_WRITE_PROBE_NOT_PERMISSION_DENIAL");
                dev.mineagent.runtime.neoforge.ui.TakeoverResumeSmokeServer.clientReadOnlyChecked=true;stage=6;return;
            }
            host.browser().executeJavaScript("document.querySelector('[data-view-id=\""+newView+"\"] [data-action=\"activate-takeover\"]').click();",host.browser().getURL(),0);stage=2;
        }catch(Exception failure){throw new IllegalStateException(failure);}}));}
        if(stage==2&&ticks%10==0){
            host.browser().executeJavaScript("""
                (()=>{const node=document.querySelector('[data-takeover-status="ACTIVE"]');if(node)window.mineagentQuery({request:JSON.stringify({channel:'takeoverProbe',viewId:node.dataset.viewId,active:true}),persistent:false,onSuccess(){},onFailure(){}});})();
                """,host.browser().getURL(),0);
        }
        if(stage==3){frameScript(newView,"document.querySelector('[data-ai-id=\"title-save\"]').click();");stage=4;}
        if(stage==4&&(resumeMode()?dev.mineagent.runtime.neoforge.ui.TakeoverResumeSmokeServer.verified:ScoreUiSmokeServer.takeoverVerified)&&!observing){observing=true;PackagePageAgent.inspectManagedView(newView).whenComplete((value,error)->mc.execute(()->{try{
            if(error!=null)throw new IllegalStateException(error);if(!value.contains("人工草稿"))throw new IllegalStateException("TAKEOVER_SAVE_UI");Files.writeString(root.resolve("saved-dom.json"),value);stage=5;
        }catch(Exception failure){throw new IllegalStateException(failure);}}));}
        if(stage==6&&dev.mineagent.runtime.neoforge.ui.TakeoverResumeSmokeServer.verified&&!screenShot){
            screenShot=true;PackagePageAgent.captureManagedView(newView).whenComplete((shot,error)->mc.execute(()->{try{if(error!=null)throw new IllegalStateException(error);Files.write(root.resolve("recovered-view.png"),shot.png());Files.writeString(root.resolve("recovery.json"),new Gson().toJson(java.util.Map.of("processId",ProcessHandle.current().pid(),"previousDocument",oldDocument,"documentId",shot.documentId(),"draft",expectedDraft(),"readOnly",true,"noModel",true,"newView",newView)));stage=7;}catch(Exception e){throw new IllegalStateException(e);}}));
        }
        if((stage==5&&!screenShot)||stage==7){screenShot=true;stage=8;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root.resolve("takeover-render.png"));captured=true;}catch(Exception e){throw new IllegalStateException(e);}});}
        if(captured){ended=true;host.close();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info(readOnlyResume()?"MINEAGENT_REVISION_RECOVERY_GRAPHICAL_OK diskDraft=true noModel=true noAutoSubmit=true":resumeMode()?"MINEAGENT_TAKEOVER_RESUME_GRAPHICAL_OK diskDraft=true noModel=true explicitPlayerSave=true":"MINEAGENT_TAKEOVER_GRAPHICAL_OK newDocument=true draftRestored=true noAutoSubmit=true explicitPlayerSave=true");mc.stop();}
        if(ticks>3600)throw new IllegalStateException("TAKEOVER_TIMEOUT stage="+stage);
    }
    static void accept(JsonObject value){newView=value.get("viewId").getAsString();if(stage==2&&value.has("active")&&value.get("active").getAsBoolean())stage=3;}
    private static boolean hasDraft(JsonObject dom){for(var e:dom.getAsJsonArray("elements")){var v=e.getAsJsonObject();if(v.get("dataAiId").getAsString().equals("title-input")&&v.get("value").getAsString().equals(expectedDraft()))return true;}return false;}
    private static void frameScript(String view,String code){var host=WebGuiHostAdapter.INSTANCE;String url=host.packageUrl(view);for(long id:host.browser().getFrameIdentifiers()){var f=host.browser().getFrame(id);if(f!=null&&!f.isMain()&&url.equals(f.getURL())){f.executeJavaScript(code,url,0);return;}}throw new IllegalStateException("TAKEOVER_FRAME_MISSING");}
}
