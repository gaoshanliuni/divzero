package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.Gson;
import net.minecraft.client.Minecraft;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Development capture probe. Does not claim a model saw the resulting image or selected a coordinate. */
public final class CaptureSmokeClient {
    private static boolean started;
    public static volatile boolean complete;
    public static volatile String failure;
    private static final Gson JSON=new Gson();
    private CaptureSmokeClient(){}
    public static void start(String view){
        if(started)return;started=true;var checks=new LinkedHashMap<String,String>();
        PackagePageAgent.captureManagedView(view).thenCompose(shot->onClient(()->{
            save("view",shot);
            return expectMissing("unknown-view","VIEW_NOT_RENDERED").thenCompose(code->onClient(()->{
                checks.put("unknown",code);script(view,"node.dataset.captureOldStyle=node.getAttribute('style');node.style.display='none';");
                return expectMissing(view,"VIEW_NOT_RENDERED");
            })).thenCompose(code->onClient(()->{
                checks.put("hidden",code);script(view,"node.style.cssText=node.dataset.captureOldStyle;delete node.dataset.captureOldStyle;const r=node.querySelector('iframe').getBoundingClientRect();const cover=document.createElement('section');cover.id='capture-test-overlay';cover.className='window';cover.dataset.viewId='capture-test-overlay';cover.style.cssText=`position:absolute;left:${r.left+40}px;top:${r.top+40}px;width:100px;height:100px;z-index:2147483646;background:#f00`;cover.textContent='OTHER VIEW';document.body.append(cover);");
                return expectMissing(view,"VIEW_OCCLUDED");
            })).thenCompose(code->onClient(()->{
                checks.put("occluded",code);script(view,"document.getElementById('capture-test-overlay')?.remove();");
                return PackagePageAgent.captureManagedView(view);
            })).thenAccept(restored->{
                if(!restored.documentId().equals(shot.documentId())||restored.width()!=shot.width()||restored.height()!=shot.height())throw new IllegalStateException("CAPTURE_RESTORE_CONTEXT");
                save("restored",restored);checks.put("restored","CAPTURED");
            });
        })).whenComplete((ignored,error)->Minecraft.getInstance().execute(()->{
            try{
                Path root=root();Files.createDirectories(root);
                if(error!=null){Files.writeString(root.resolve("failure.txt"),error.toString());failure=error.toString();return;}
                Files.writeString(root.resolve("negative-checks.json"),JSON.toJson(checks));complete=true;
                dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("MINEAGENT_VIEW_CAPTURE_OK realTexture=true unknownRejected=true hiddenRejected=true occludedRejected=true restored=true");
            }catch(Exception errorResult){failure=errorResult.toString();}
        }));
    }
    private static CompletableFuture<String> expectMissing(String view,String expected){
        return PackagePageAgent.captureManagedView(view).handle((shot,error)->{
            if(error==null)throw new IllegalStateException("CAPTURE_NEGATIVE_UNEXPECTED_IMAGE");
            for(int i=0;i<8&&error.getCause()!=null;i++)error=error.getCause();
            if(!expected.equals(error.getMessage()))throw new IllegalStateException("CAPTURE_NEGATIVE_STATUS expected="+expected+" actual="+error.getMessage(),error);
            return error.getMessage();
        });
    }
    private static void script(String view,String body){var host=WebGuiHostAdapter.INSTANCE;host.browser().executeJavaScript("(()=>{const node=[...document.querySelectorAll('.window')].find(n=>n.dataset.viewId==="+JSON.toJson(view)+");"+body+"})();",host.browser().getURL(),0);}
    private static void save(String name,PackageViewCapture.Captured shot){
        try{
            Path root=root();Files.createDirectories(root);Files.write(root.resolve(name+".png"),shot.png());
            Files.writeString(root.resolve(name+".json"),JSON.toJson(Map.of("documentId",shot.documentId(),"layoutIdentity",shot.layoutIdentity(),
                    "width",shot.width(),"height",shot.height(),"frameX",shot.frameX(),"frameY",shot.frameY(),"scaleX",shot.scaleX(),"scaleY",shot.scaleY(),
                    "sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(shot.png())),"mode","NATIVE_CAPTURE_PROBE_NOT_MODEL_VISUAL_ANALYSIS")));
        }catch(Exception e){throw new IllegalStateException("CAPTURE_EVIDENCE_WRITE",e);}
    }
    private static Path root(){return Minecraft.getInstance().gameDirectory.toPath().resolve("capture-evidence");}
    private static <T> CompletableFuture<T> onClient(Supplier<CompletableFuture<T>> work){var result=new CompletableFuture<T>();Minecraft.getInstance().execute(()->{try{work.get().whenComplete((v,e)->{if(e!=null)result.completeExceptionally(e);else result.complete(v);});}catch(Exception e){result.completeExceptionally(e);}});return result;}
}
