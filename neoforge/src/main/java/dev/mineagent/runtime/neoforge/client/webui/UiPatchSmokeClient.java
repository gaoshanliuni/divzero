package dev.mineagent.runtime.neoforge.client.webui;
import com.cinemamod.mcef.MCEF;
import com.google.gson.*;
import dev.mineagent.runtime.neoforge.ui.UiPatchSmokeServer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.cef.browser.*;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import java.nio.file.*;
import java.util.*;

/** Real GUI test driver, not the patch Coder. Negative probes use the real page SDK, never backend mutation helpers. */
@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class UiPatchSmokeClient {
    private static final Gson JSON=new Gson();private static int ticks,phase;private static boolean opened,busy,ended,backup;
    private static String failure;private static CefMessageRouter router;private static final Set<String> denied=new HashSet<>();
    private static String pendingScreenshot;private static int screenshotTick;private static Runnable afterScreenshot;
    private static String previousScreen="";private static boolean respawnRequested;
    private static final boolean AUTO=Boolean.getBoolean("mineagent.uiPatchAutoSwap");private static boolean oldProbed;
    private static final boolean INCOMPATIBLE=Boolean.getBoolean("mineagent.uiPatchIncompatible");
    private static String quote(String s){return JSON.toJson(s);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.uiPatchSmoke"))return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen screen){backup=true;var f=screen.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(screen)).proceed(false,false);}
        if(!opened&&mc.player!=null&&mc.player.isDeadOrDying()){
            if(!respawnRequested){respawnRequested=true;mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));}
            return;
        }
        if(mc.player!=null&&!opened){opened=true;host.open();}
        String screenName=mc.screen==null?"none":mc.screen.getClass().getName();
        if(opened&&!screenName.equals(previousScreen)){previousScreen=screenName;Files.createDirectories(root());Files.writeString(root().resolve("screen-transitions.txt"),ticks+" "+screenName+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
        if(pendingScreenshot!=null&&ticks>=screenshotTick){
            String name=pendingScreenshot;Runnable next=afterScreenshot;pendingScreenshot=null;afterScreenshot=null;
            net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve(name+"-render.png"));busy=false;next.run();}catch(Exception e){failure=e.toString();}});
        }
        if(host.ready()&&UiClientSessions.current()!=null){register();
            if(ticks%20==0&&phase==0&&UiPatchSmokeServer.sourceId!=null)main("""
                if(!document.querySelector('#generation-prompt'))document.querySelector('#open-generation').click();
                const card=[...document.querySelectorAll('.generation-job:not(.patch-job)')].find(n=>n.dataset.packageId===PKG);if(!card)return;
                const source=card.querySelector('select[aria-label="已有计分目标"]');
                if(!window.__patchBound&&source&&[...source.options].some(o=>o.value===SOURCE)){window.__patchBound=true;source.value=SOURCE;source.dispatchEvent(new Event('change',{bubbles:true}));[...card.querySelectorAll('button')].find(b=>b.textContent==='创建独立绑定视图').click();}
                const open=card.querySelector('[data-source-id="'+SOURCE+'"]');if(open&&!window.__patchOpened){window.__patchOpened=true;open.click();}
                """.replace("PKG",quote(UiPatchSmokeServer.PACKAGE.toString())).replace("SOURCE",quote(UiPatchSmokeServer.sourceId)));
            if(phase==0&&ready(UiPatchSmokeServer.baseView)&&!busy){
                observe(UiPatchSmokeServer.baseView,"before-dom",dom->{
                    if(!dom.get("visibleText").getAsString().contains("Alice"))return;
                    frame(UiPatchSmokeServer.baseView,"const input=document.querySelector('input');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,'人工改版草稿');input.dispatchEvent(new InputEvent('input',{bubbles:true}));");
                    if(UiPatchSmokeServer.RESUMING){phase=1;return;}
                    phase=1;main("document.querySelector('[data-patch-package=\""+UiPatchSmokeServer.PACKAGE+"\"]').click();const input=document.querySelector('#ui-patch-prompt');input.value="+quote(patchPrompt())+";input.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#ui-patch-submit').click();");
                });
            }
            if(phase==1&&UiPatchSmokeServer.staged){phase=2;patchButton("patchPreview");}
            if(phase==2&&UiPatchSmokeServer.candidateView==null&&ticks%20==0)patchButton("patchPreview");
            if(phase==2&&ready(UiPatchSmokeServer.candidateView)&&!busy){observe(UiPatchSmokeServer.candidateView,"candidate-dom",dom->{
                if(!dom.get("visibleText").getAsString().contains("Alice"))return;
                if(!dom.get("visibleText").getAsString().contains(UiPatchSmokeServer.MARKER))throw new IllegalStateException("CANDIDATE_MARKER_NOT_RENDERED");
                captureAndScreenshot(UiPatchSmokeServer.candidateView,"candidate",()->{phase=3;probe(UiPatchSmokeServer.candidateView,"candidate");});
            });}
            if(phase==3&&denied.contains("candidate")){phase=4;patchButton(AUTO?"patchApplySwap":"patchApply");}
            if(phase==4&&!UiPatchSmokeServer.applied&&ticks%20==0)patchButton(AUTO?"patchApplySwap":"patchApply");
            if(AUTO&&phase==4&&!oldProbed&&PackageContentClient.session(UiPatchSmokeServer.baseView)==null&&host.packageUrl(UiPatchSmokeServer.baseView)!=null&&!busy){
                oldProbed=true;probe(UiPatchSmokeServer.baseView,"old");observe(UiPatchSmokeServer.baseView,"old-draft-dom",dom->{if(!hasDraft(dom))throw new IllegalStateException("OLD_DRAFT_LOST");});
            }
            if(INCOMPATIBLE&&phase==4&&UiPatchSmokeServer.operationId!=null&&!busy&&denied.contains("old")){
                var outcome=ContentHotSwapClient.outcome(UUID.fromString(UiPatchSmokeServer.operationId));
                if(outcome!=null&&outcome.state().equals("FAILED")){
                    if(outcome.commitAcknowledged()||UiPatchSmokeServer.applied)throw new IllegalStateException("INCOMPATIBLE_CANDIDATE_APPLIED");
                    Files.writeString(root().resolve("transition-failure.json"),JSON.toJson(outcome));
                    observe(UiPatchSmokeServer.baseView,"rejected-old-dom",dom->{if(!hasDraft(dom))throw new IllegalStateException("REJECTED_DRAFT_LOST");captureAndScreenshot(UiPatchSmokeServer.baseView,"rejected-old",()->{phase=23;patchButton("patchCancel");});});
                }
            }
            if(INCOMPATIBLE&&phase==23&&UiPatchSmokeServer.state.equals("CANCELLED")&&Files.exists(root().resolve("rejected.json"))){
                ended=true;Files.writeString(root().resolve("negative-result.json"),JSON.toJson(Map.of("candidateIncompatibleRejected",true,"oldDraftPreserved",true,"headUnchanged",true,"writesDenied",true)));
                dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_UI_PATCH_INCOMPATIBLE_OK headUnchanged=true draftPreserved=true");host.close();mc.stop();
            }
            if(!AUTO&&phase==4&&UiPatchSmokeServer.applied&&ticks%20==0)openCurrent(UiPatchSmokeServer.baseRevision+1);
            if(phase==4&&ready(UiPatchSmokeServer.updatedView)&&!busy&&(!AUTO||host.packageUrl(UiPatchSmokeServer.baseView)==null)){observe(UiPatchSmokeServer.updatedView,"applied-dom",dom->{
                if(!dom.get("visibleText").getAsString().contains("Alice"))return;
                if(!dom.get("visibleText").getAsString().contains(UiPatchSmokeServer.MARKER))throw new IllegalStateException("UPDATED_MARKER_MISSING");
                if(AUTO&&(!hasDraft(dom)||!PackageContentClient.session(UiPatchSmokeServer.updatedView).binding().capabilities().equals(Set.of("scoreview.read"))))throw new IllegalStateException("AUTO_RESTORE_FAILED");
                captureAndScreenshot(UiPatchSmokeServer.updatedView,"applied",()->phase=5);
            });}
            if(AUTO&&phase==5){if(!denied.contains("old"))throw new IllegalStateException("OLD_WRITE_PROBE_MISSING");phase=6;}
            if(!AUTO&&phase==5&&!busy){observe(UiPatchSmokeServer.baseView,"old-draft-dom",dom->{
                boolean draft=false;for(var element:dom.getAsJsonArray("elements"))if(element.getAsJsonObject().get("value").getAsString().equals("人工改版草稿"))draft=true;
                if(!draft)throw new IllegalStateException("OLD_DRAFT_LOST");phase=6;probe(UiPatchSmokeServer.baseView,"old");
            });}
            if(phase==6&&denied.contains("old")){phase=7;patchButton(AUTO?"patchUndoSwap":"patchRollback");}
            if(phase==7&&!UiPatchSmokeServer.rolledBack&&ticks%20==0)patchButton(AUTO?"patchUndoSwap":"patchRollback");
            if(!AUTO&&phase==7&&UiPatchSmokeServer.rolledBack&&ticks%20==0)openCurrent(UiPatchSmokeServer.baseRevision+2);
            if(phase==7&&ready(UiPatchSmokeServer.rollbackView)&&!busy&&(!AUTO||host.packageUrl(UiPatchSmokeServer.updatedView)==null)){observe(UiPatchSmokeServer.rollbackView,"rollback-dom",dom->{
                if(!dom.get("visibleText").getAsString().contains("Alice"))return;
                if(dom.get("visibleText").getAsString().contains(UiPatchSmokeServer.MARKER))throw new IllegalStateException("ROLLBACK_MARKER_STILL_PRESENT");
                if(AUTO&&!hasDraft(dom))throw new IllegalStateException("ROLLBACK_DRAFT_LOST");
                captureAndScreenshot(UiPatchSmokeServer.rollbackView,"rollback",()->phase=9);
            });}
            if(phase==9&&!ended){ended=true;Files.writeString(root().resolve("client-result.json"),JSON.toJson(Map.of("candidateReadOnly",true,"oldSessionReadOnly",true,"oldDraftPreserved",true,"applyAndRollbackRendered",true,"automaticReadonlyRestoreAndSourceReclamation",AUTO,"driver","TRUSTED_GUI_DRIVER_REAL_MODEL_PATCH_CODER")));dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_UI_PATCH_CLIENT_OK candidate=true oldDraft=true apply=true rollback=true auto={}",AUTO);host.close();mc.stop();}
        }
        if(!ended&&(failure!=null||ticks>7200)){ended=true;Files.createDirectories(root());Files.writeString(root().resolve("failure.json"),JSON.toJson(Map.of("phase",phase,"screen",screenName,"failure",failure==null?"TIMEOUT":failure)));throw new IllegalStateException("UI_PATCH_GRAPHICAL_FAILED: "+failure);}
    }
    private static boolean ready(String view){return view!=null&&WebGuiHostAdapter.INSTANCE.packageLoaded(view)&&PackageContentClient.session(view)!=null;}
    private static boolean hasDraft(JsonObject dom){for(var e:dom.getAsJsonArray("elements"))if(e.getAsJsonObject().get("value").getAsString().equals("人工改版草稿"))return true;return false;}
    private static String patchPrompt(){return "只修改 ui/index.html 和 ui/style.css：在 Canvas 下方添加一行可见文字‘"+UiPatchSmokeServer.MARKER+"’，保留原说明和之前的文字；标题字号改16px并添加下边线。"+
            (INCOMPATIBLE?"为标题输入 input 添加 name=\"updated_title_field\" 属性，这是本次要求；保留原来的 data-ai-id、id、type 以及其他属性。":"")+
            "保持半透明 glass-sage 风格和所有现有行为。不要修改 ui/app.js，不得自动保存、变更真实分数/视图数据或改变 Canvas 命中逻辑。验收标签（不要显示在页面）："+UiPatchSmokeServer.REQUEST_TOKEN;}
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("ui-patch-evidence").resolve(UiPatchSmokeServer.EVIDENCE_RUN);}
    private static void captureAndScreenshot(String view,String name,Runnable next){
        phase=20;busy=true;
        // The swap preserves the old z-order; the driver explicitly focuses the target before a visual proof.
        main("const node=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==="+quote(view)+");node?.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true}));");
        java.util.concurrent.CompletableFuture.delayedExecutor(200,java.util.concurrent.TimeUnit.MILLISECONDS).execute(()->Minecraft.getInstance().execute(()->capturePainted(view,name,next)));
    }
    private static void capturePainted(String view,String name,Runnable next){
        PackagePageAgent.captureManagedView(view).whenComplete((shot,error)->Minecraft.getInstance().execute(()->{
            try{
                if(error!=null)throw new IllegalStateException(error);
                Files.write(root().resolve(name+"-view.png"),shot.png());
                Files.writeString(root().resolve(name+"-capture.json"),JSON.toJson(Map.of("documentId",shot.documentId(),"layoutIdentity",shot.layoutIdentity(),"width",shot.width(),"height",shot.height(),"mode","NATIVE_ROI_PAINT_FENCED")));
                pendingScreenshot=name;screenshotTick=ticks+3;afterScreenshot=next;
            }catch(Exception e){failure=e.toString();}
        }));
    }
    private static void main(String body){var h=WebGuiHostAdapter.INSTANCE;h.browser().executeJavaScript("(()=>{"+body+"})();",h.browser().getURL(),0);}
    private static void frame(String view,String script){var h=WebGuiHostAdapter.INSTANCE;String url=h.packageUrl(view);for(long id:h.browser().getFrameIdentifiers()){var f=h.browser().getFrame(id);if(f!=null&&!f.isMain()&&url.equals(f.getURL())){f.executeJavaScript(script,url,0);return;}}throw new IllegalStateException("PATCH_FRAME_MISSING");}
    private static void patchButton(String action){main("const card=[...document.querySelectorAll('.patch-job')].find(n=>n.dataset.operationId==="+quote(UiPatchSmokeServer.operationId)+");const button=card?.querySelector('[data-patch-action="+action+"]');if(button&&!button.disabled&&!window.__did_"+action+"){window.__did_"+action+"=true;button.click();}");}
    private static void openCurrent(long revision){main("const card=[...document.querySelectorAll('.generation-job:not(.patch-job)')].find(n=>n.dataset.packageId==="+quote(UiPatchSmokeServer.PACKAGE.toString())+"&&Number(n.dataset.packageRevision)==="+revision+");const button=card?.querySelector('[data-target-view-id=\""+UiPatchSmokeServer.targetViewId+"\"]');if(button&&!button.disabled&&!window.__patchOpen"+revision+"){window.__patchOpen"+revision+"=true;button.click();}");}
    private static void observe(String view,String name,java.util.function.Consumer<JsonObject> check){busy=true;PackagePageAgent.inspectManagedView(view).whenComplete((value,error)->Minecraft.getInstance().execute(()->{busy=false;try{if(error!=null)throw new IllegalStateException(error);Files.createDirectories(root());Files.writeString(root().resolve(name+".json"),value);check.accept(JsonParser.parseString(value).getAsJsonObject());}catch(Exception e){failure=e.toString();}}));}
    private static void probe(String view,String tag){frame(view,"window.mineagentUi.patch(1,{title:'FORBIDDEN_PREVIEW_WRITE'},crypto.randomUUID()).then(()=>report('UNEXPECTED_SUCCESS'),e=>report(String(e.message)));function report(code){window.mineagentPatchSmokeQuery({request:JSON.stringify({view:"+quote(view)+",tag:"+quote(tag)+",code}),persistent:false,onSuccess(){},onFailure(){}})}");}
    private static void register(){if(router!=null)return;router=CefMessageRouter.create(new CefMessageRouter.CefMessageRouterConfig("mineagentPatchSmokeQuery","mineagentPatchSmokeQueryCancel"));router.addHandler(new CefMessageRouterHandlerAdapter(){
        @Override public boolean onQuery(CefBrowser browser,CefFrame frame,long id,String request,boolean persistent,CefQueryCallback callback){if(frame==null||frame.isMain()||persistent||request.length()>2048||!WebGuiHostAdapter.INSTANCE.owns(browser)){callback.failure(403,"SOURCE");return true;}String url=frame.getURL();Minecraft.getInstance().execute(()->{try{var data=JsonParser.parseString(request).getAsJsonObject();if(!url.equals(WebGuiHostAdapter.INSTANCE.packageUrl(data.get("view").getAsString())))throw new IllegalStateException("PROBE_FRAME");String code=data.get("code").getAsString();Files.writeString(root().resolve(data.get("tag").getAsString()+"-write-probe.json"),data.toString());if(!(code.contains("PERMISSION_DENIED")||code.contains("PREVIEW_READ_ONLY")||code.contains("CONTENT_SOURCE_REJECTED")))throw new IllegalStateException("WRITE_WAS_NOT_DENIED: "+code);denied.add(data.get("tag").getAsString());callback.success("{}");}catch(Exception e){failure=e.toString();callback.failure(400,"PROBE_FAILED");}});return true;}
    },true);MCEF.getClient().getHandle().addMessageRouter(router);}
}
