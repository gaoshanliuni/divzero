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
    public static void defaults(String id){
        var asset=NativePackageViews.asset(id);var settings=asset.assets().get(dev.mineagent.runtime.core.ui.UiViewSettings.PATH);if(settings==null)return;
        var entries=asset.runtimePackage().entrypoints().values().stream().map(dev.mineagent.runtime.api.packages.RuntimeEntrypoint::path).collect(java.util.stream.Collectors.toSet());
        var value=dev.mineagent.runtime.core.ui.UiViewSettings.parse(new String(settings.bytes(),java.nio.charset.StandardCharsets.UTF_8),entries).get(asset.entry());if(value==null)return;
        var area=NativePackageViews.layout(id).getAsJsonObject("area");var bounds=new dev.mineagent.runtime.core.ui.UiPresentationAction.Placement(value.anchor(),value.width(),value.height(),value.offsetX(),value.offsetY(),value.opacity()).resolve(new dev.mineagent.runtime.core.ui.UiPresentationAction.Bounds(area.get("x").getAsDouble(),area.get("y").getAsDouble(),area.get("width").getAsDouble(),area.get("height").getAsDouble()));
        var layout=new JsonObject();layout.add("bounds",JSON.toJsonTree(bounds));layout.addProperty("opacity",value.opacity()==null?1:value.opacity());NativePackageViews.restoreLayout(id,layout);
    }
    public static void restore(String id){
        String key=scope(id),document=NativePackageViews.document(id);var storage=store();
        CompletableFuture.supplyAsync(()->{try{return storage.load(key);}catch(Exception e){throw new CompletionException(e);}},IO).whenComplete((encoded,error)->Minecraft.getInstance().execute(()->{
            if(!NativePackageViews.owns(id)||!NativePackageViews.document(id).equals(document))return;
            if(error!=null){NativeWorkspaceScreen.notice("UI_LAYOUT_LOAD_FAILED");return;}
            try{var value=JsonParser.parseString(encoded).getAsJsonObject();if(!value.isEmpty())NativePackageViews.restoreLayout(id,value);}catch(Exception invalid){NativeWorkspaceScreen.notice("UI_LAYOUT_INVALID");}
        }));
    }
    public static CompletableFuture<Void> save(String id,JsonObject layout){
        var saved=layout.deepCopy();saved.remove("opacityPaint");saved.remove("hostDocumentId");saved.remove("viewId");
        String key=scope(id),document=NativePackageViews.document(id),encoded=saved.toString();var storage=store();var result=new CompletableFuture<Void>();
        CompletableFuture.runAsync(()->{try{storage.save(key,encoded);if(!storage.load(key).equals(encoded))throw new IllegalStateException("UI_LAYOUT_READBACK_FAILED");}catch(Exception e){throw new CompletionException(e);}},IO).whenComplete((ignored,error)->Minecraft.getInstance().execute(()->{
            if(error!=null)result.completeExceptionally(error);else if(!NativePackageViews.owns(id)||!document.equals(NativePackageViews.document(id)))result.completeExceptionally(new IllegalStateException("STALE_VIEW"));else result.complete(null);
        }));return result;
    }
    public static void flush(){try{CompletableFuture.runAsync(()->{},IO).get(2,TimeUnit.SECONDS);}catch(Exception error){if(error instanceof InterruptedException)Thread.currentThread().interrupt();}}
    private NativePackagePlacement(){}
}
