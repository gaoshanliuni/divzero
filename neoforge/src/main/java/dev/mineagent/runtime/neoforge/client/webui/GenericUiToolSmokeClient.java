package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.neoforge.ui.GenericUiToolSmokeServer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;

@EventBusSubscriber(modid="mineagent_runtime",value=Dist.CLIENT)
public final class GenericUiToolSmokeClient {
    private static int ticks,phase;private static boolean opened,backup,busy,ended;private static String failure;
    private static final Set<String> candidates=new HashSet<>();private static boolean candidateChecked;
    private static String stateProbeView;
    private static boolean resumed;private static String previousScreen="";
    @SubscribeEvent public static void tick(ClientTickEvent.Post event)throws Exception{
        if(!Boolean.getBoolean("mineagent.genericUiToolSmoke")||ended)return;var mc=Minecraft.getInstance();var host=WebGuiHostAdapter.INSTANCE;ticks++;
        if(!backup&&mc.screen instanceof net.minecraft.client.gui.screens.BackupConfirmScreen s){backup=true;var f=s.getClass().getDeclaredField("onProceed");f.setAccessible(true);((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener)f.get(s)).proceed(false,false);}
        if(mc.player!=null&&!opened){opened=true;host.open();}
        String screen=mc.screen==null?"none":mc.screen.getClass().getName();if(opened&&!screen.equals(previousScreen)){previousScreen=screen;Files.createDirectories(root());Files.writeString(root().resolve("screen-transitions.txt"),ticks+" "+screen+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
        if(GenericUiToolSmokeServer.RESUMING&&!resumed&&GenericUiToolSmokeServer.resumeParentRevision>0&&mc.player!=null){resumed=true;net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new dev.mineagent.runtime.neoforge.network.MineAgentPayloads.TaskCommand("resume",Map.of("taskId",GenericUiToolSmokeServer.parentTaskId,"expectedRevision",Long.toString(GenericUiToolSmokeServer.resumeParentRevision))));}
        if(host.ready()&&UiClientSessions.current()!=null&&ticks%10==0){
            String script="if(!document.querySelector('#generation-prompt'))document.querySelector('#open-generation').click();const a=document.querySelector('#generation-agent');if(!a||![...a.options].some(o=>o.value===AGENT))return;"+
                "if(!window.__genericTaskSubmitted){window.__genericTaskSubmitted=true;a.value=AGENT;a.dispatchEvent(new Event('change',{bubbles:true}));const p=document.querySelector('#generation-prompt');p.value=PROMPT;p.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#ui-task-submit').click();}"+
                "if(document.querySelector('#ui-task-receipt')&&!window.__genericRetry){window.__genericRetry=true;document.querySelector('#ui-task-submit').click();}"+
                (GenericUiToolSmokeServer.patchMode()?
                "const patch=[...document.querySelectorAll('.patch-job')].find(n=>n.dataset.operationId===OPERATION);if(patch&&!window.__genericCandidate){const b=patch.querySelector('[data-patch-action=patchPreview]');if(b){window.__genericCandidate=true;b.click();}}"+
                (GenericUiToolSmokeServer.verified?"const card=[...document.querySelectorAll('.generation-job:not(.patch-job)')].find(n=>n.dataset.packageId==="+new Gson().toJson(GenericUiToolSmokeServer.PATCH_PACKAGE)+"&&Number(n.dataset.packageRevision)==="+(GenericUiToolSmokeServer.PATCH_REVISION+1)+");if(card&&!window.__genericOpen){window.__genericOpen=true;[...card.querySelectorAll('button')].find(n=>n.textContent==='打开独立预览').click();}":""):
                "const card=[...document.querySelectorAll('.generation-job:not(.patch-job)')].find(n=>n.dataset.operationId===OPERATION);if(card&&!window.__genericOpen){const b=[...card.querySelectorAll('button')].find(n=>n.textContent==='打开独立预览');if(b){window.__genericOpen=true;b.click();}}");
            if(GenericUiToolSmokeServer.RESUMING)script="window.__genericTaskSubmitted=true;window.__genericRetry=true;"+script;
            script=script.replace("AGENT",new Gson().toJson(GenericUiToolSmokeServer.agentId)).replace("PROMPT",new Gson().toJson(GenericUiToolSmokeServer.PROMPT)).replace("OPERATION",new Gson().toJson(GenericUiToolSmokeServer.operationId));host.browser().executeJavaScript("(()=>{"+script+"})();",host.browser().getURL(),0);
        }
        if(GenericUiToolSmokeServer.patchMode()&&GenericUiToolSmokeServer.proposalReady&&!candidateChecked&&!busy){
            String view=matchingView();if(view!=null){busy=true;PackagePageAgent.inspectManagedView(view).whenComplete((value,error)->mc.execute(()->{try{
                if(error!=null)throw new IllegalStateException(error);if(!JsonParser.parseString(value).getAsJsonObject().get("visibleText").getAsString().contains(GenericUiToolSmokeServer.MARKER)){busy=false;return;}
                Files.createDirectories(root());Files.writeString(root().resolve("candidate-dom.json"),value);
                captureSettled(view,0).whenComplete((shot,err)->mc.execute(()->{try{if(err!=null)throw new IllegalStateException(err);Files.write(root().resolve("candidate-view.png"),shot.png());candidates.add(view);candidateChecked=true;busy=false;
                    if(Boolean.getBoolean("mineagent.genericUiStateProbe")){
                        stateProbeView=view;frame(view,"const out=document.createElement('p');out.id='candidate-state-probe';document.body.append(out);(async()=>{try{const s=await window.mineagentState.get('candidate_probe');await window.mineagentState.put('candidate_probe',s.revision,{forbidden:true});out.textContent='CANDIDATE_STATE_UNEXPECTED_WRITE';}catch(e){out.textContent='CANDIDATE_STATE_DENIED '+e.message;}})();");
                    }else applyCandidate();
                }catch(Exception e){failure=e.toString();}}));
            }catch(Exception e){failure=e.toString();}}));}
        }
        if(stateProbeView!=null&&!busy){busy=true;PackagePageAgent.inspectManagedView(stateProbeView).whenComplete((value,error)->mc.execute(()->{try{
            busy=false;if(error!=null)throw new IllegalStateException(error);String text=JsonParser.parseString(value).getAsJsonObject().get("visibleText").getAsString();
            if(text.contains("CANDIDATE_STATE_UNEXPECTED_WRITE"))throw new IllegalStateException("CANDIDATE_STATE_WRITE_ALLOWED");
            if(text.contains("CANDIDATE_STATE_DENIED UI_STATE_PERMISSION_DENIED")){Files.writeString(root().resolve("candidate-state-denied.json"),value);stateProbeView=null;applyCandidate();}
        }catch(Exception e){failure=e.toString();}}));}
        if(GenericUiToolSmokeServer.verified&&phase==0&&!busy){
            // Preview identity comes from the actual host mount, not a server content path.
            String view=matchingView();
            if(view!=null){busy=true;PackagePageAgent.inspectManagedView(view).whenComplete((value,error)->mc.execute(()->{try{
                if(error!=null)throw new IllegalStateException(error);var dom=JsonParser.parseString(value).getAsJsonObject();
                if(!dom.get("visibleText").getAsString().contains(GenericUiToolSmokeServer.patchMode()?GenericUiToolSmokeServer.MARKER:"巡逻")||dom.getAsJsonArray("elements").size()<3){busy=false;return;}
                Files.createDirectories(root());Files.writeString(root().resolve("dom.json"),value);phase=1;
                captureSettled(view,0).whenComplete((shot,captureError)->mc.execute(()->{try{if(captureError!=null)throw new IllegalStateException(captureError);Files.write(root().resolve("view.png"),shot.png());phase=2;busy=false;}catch(Exception e){failure=e.toString();}}));
            }catch(Exception e){failure=e.toString();}}));}
        }
        if(phase==2&&!busy){busy=true;net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{try(image){image.writeToFile(root().resolve("render.png"));phase=3;}catch(Exception e){failure=e.toString();}});}
        if(phase==3){Files.writeString(root().resolve("client-result.json"),new Gson().toJson(Map.of("taskStartedInWebGui",true,"retriedSameSubmission",true,"actualNetworkPreview",true,"generatedAgain",!GenericUiToolSmokeServer.patchMode(),"explicitCandidateApply",candidateChecked,"mode","MODEL_TOOL_GENERATION_NOT_MODEL_DOM_ACTION")));ended=true;host.close();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_GENERIC_UI_TOOL_CLIENT_OK realPreview=true");mc.stop();}
        if(failure!=null||ticks>7200){Files.createDirectories(root());Files.writeString(root().resolve("failure.json"),new Gson().toJson(Map.of("phase",phase,"screen",screen,"error",failure==null?"TIMEOUT":failure)));throw new IllegalStateException("GENERIC_UI_TOOL_SMOKE_FAILED: "+failure);}
    }
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve(GenericUiToolSmokeServer.directory());}
    private static java.util.concurrent.CompletableFuture<PackageViewCapture.Captured> captureSettled(String view,int attempt){
        var result=new java.util.concurrent.CompletableFuture<PackageViewCapture.Captured>();
        java.util.concurrent.CompletableFuture.delayedExecutor(250,java.util.concurrent.TimeUnit.MILLISECONDS).execute(()->Minecraft.getInstance().execute(()->
            PackagePageAgent.captureManagedView(view).whenComplete((image,error)->Minecraft.getInstance().execute(()->{
                if(error==null){result.complete(image);return;}Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();
                if(attempt<3&&"STALE_VIEW".equals(cause.getMessage()))captureSettled(view,attempt+1).whenComplete((v,e)->{if(e==null)result.complete(v);else result.completeExceptionally(e);});else result.completeExceptionally(error);
            }))));return result;
    }
    private static void applyCandidate(){var host=WebGuiHostAdapter.INSTANCE;host.browser().executeJavaScript("[...document.querySelectorAll('.patch-job')].find(n=>n.dataset.operationId==="+new Gson().toJson(GenericUiToolSmokeServer.operationId)+").querySelector('[data-patch-action=patchApply]').click();",host.browser().getURL(),0);}
    private static void frame(String view,String script){var host=WebGuiHostAdapter.INSTANCE;String url=host.packageUrl(view);for(long id:host.browser().getFrameIdentifiers()){var f=host.browser().getFrame(id);if(f!=null&&!f.isMain()&&url.equals(f.getURL())){f.executeJavaScript(script,url,0);return;}}throw new IllegalStateException("STATE_PROBE_FRAME_MISSING");}
    private static String matchingView(){var host=WebGuiHostAdapter.INSTANCE;return host.loadedPreviewViews().stream().filter(v->{var p=host.viewPackage(v);return !candidates.contains(v)&&p!=null&&p.packageId().toString().equals(GenericUiToolSmokeServer.packageId)&&p.revision()==GenericUiToolSmokeServer.revision;}).findFirst().orElse(null);}
}
