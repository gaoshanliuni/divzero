package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import dev.mineagent.runtime.client.webui.UiStateStore;
import dev.mineagent.runtime.api.ui.WorldUiProtocol;
import net.minecraft.client.Minecraft;
import java.util.*;
import java.util.concurrent.*;

/** Per-world native geometry preferences. They never retain sessions or trigger business writes. */
public final class NativePackagePlacement {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(Thread.ofVirtual().name("native-package-layout").factory());
    private static final Gson JSON=new Gson();
    private static UiStateStore store(){return new UiStateStore(Minecraft.getInstance().gameDirectory.toPath().resolve("mineagent-runtime-data/native-package-layouts"));}
    private static String scope(String id){var mc=Minecraft.getInstance();var shell=NativeWorkspaceConnection.current();if(shell==null)throw new IllegalStateException("VIEW_NOT_RENDERED");var asset=NativePackageViews.asset(id);var session=NativePackageViews.rawSession(id);return JSON.toJson(List.of(mc.getCurrentServer()==null?"integrated":mc.getCurrentServer().ip,shell.binding().worldId(),shell.binding().viewerPlayerId(),asset.runtimePackage().packageId(),asset.entry(),session==null?"preview":WorldUiProtocol.localStateTarget(session.binding()),session!=null&&session.binding().preview()));}
    public static void restore(String id){
        String key=scope(id),document=NativePackageViews.document(id);var storage=store();
        CompletableFuture.supplyAsync(()->{try{return storage.load(key);}catch(Exception e){throw new CompletionException(e);}},IO).whenComplete((encoded,error)->Minecraft.getInstance().execute(()->{
            if(!NativePackageViews.owns(id)||!NativePackageViews.document(id).equals(document))return;
            if(error!=null){NativeWorkspaceScreen.notice("UI_LAYOUT_LOAD_FAILED");return;}
            try{var value=JsonParser.parseString(encoded).getAsJsonObject();if(!value.isEmpty())NativePackageViews.restoreLayout(id,value);}catch(Exception invalid){NativeWorkspaceScreen.notice("UI_LAYOUT_INVALID");}
        }));
    }
    public static CompletableFuture<Void> save(String id,JsonObject layout){
        String key=scope(id),document=NativePackageViews.document(id),encoded=layout.toString();var storage=store();var result=new CompletableFuture<Void>();
        CompletableFuture.runAsync(()->{try{storage.save(key,encoded);if(!storage.load(key).equals(encoded))throw new IllegalStateException("UI_LAYOUT_READBACK_FAILED");}catch(Exception e){throw new CompletionException(e);}},IO).whenComplete((ignored,error)->Minecraft.getInstance().execute(()->{
            if(error!=null)result.completeExceptionally(error);else if(!NativePackageViews.owns(id)||!document.equals(NativePackageViews.document(id)))result.completeExceptionally(new IllegalStateException("STALE_VIEW"));else result.complete(null);
        }));return result;
    }
    public static void flush(){try{CompletableFuture.runAsync(()->{},IO).get(2,TimeUnit.SECONDS);}catch(Exception error){if(error instanceof InterruptedException)Thread.currentThread().interrupt();}}
    private NativePackagePlacement(){}
}
