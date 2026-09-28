package dev.mineagent.runtime.neoforge.client.nativeui;

import com.google.gson.*;
import dev.mineagent.runtime.agent.ui.UiAgentController;
import dev.mineagent.runtime.api.ui.*;
import dev.mineagent.runtime.neoforge.client.webui.*;
import net.minecraft.client.Minecraft;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Observes only a single native content root. The workspace and permission controls are never targets. */
public final class NativePackageAgent {
    private static final Gson JSON=new Gson();
    private static final Map<String,Port> controls=new HashMap<>();
    private NativePackageAgent(){}
    public static UiAgentController.Port control(String id,boolean presentationOnly){
        if(controls.containsKey(id))throw new IllegalStateException("UI_CONTROL_BUSY");
        if(!NativePackageViews.presentationReady(id))throw new IllegalStateException("VIEW_NOT_RENDERED");
        var port=new Port(id,presentationOnly);controls.put(id,port);return port;
    }
    public static CompletableFuture<String> inspect(String id){return onClient(()->CompletableFuture.completedFuture(observe(id).toString()));}
    private static JsonObject observe(String id){
        var result=JSON.toJsonTree(NativePackageViews.inspect(id)).getAsJsonObject();if(!result.get("status").getAsString().equals("OBSERVED"))throw new IllegalStateException("VIEW_NOT_RENDERED");
        result.add("hostPresentation",UiPresentationClient.observe(id));result.addProperty("observationId",NativePackageViews.identity(id));result.addProperty("title",NativePackageViews.asset(id).runtimePackage().name());
        if(result.toString().length()>60000)throw new IllegalStateException("NATIVE_OBSERVATION_BUDGET");return result;
    }
    public static CompletableFuture<PackageViewCapture.Captured> capture(String id){return onClient(()->NativePackageViews.capture(id).thenApply(shot->new PackageViewCapture.Captured(shot.documentId(),shot.layoutIdentity(),shot.width(),shot.height(),shot.frameX(),shot.frameY(),shot.scaleX(),shot.scaleY(),shot.png())));}
    public static void cancel(String id){var port=controls.get(id);if(port!=null)port.cancel();}
    public static void clear(){for(var port:List.copyOf(controls.values()))port.cancel();}
    private static final class Port implements UiAgentController.Port {
        final String id,document;final boolean presentationOnly;final UiProtocol.Session authority;
        boolean cancelled;Runnable interrupt=()->{};JsonObject observation;UiCapture.Image image;long deadline;
        private record Applied(String action,CompletableFuture<String> result){}
        final Map<String,Applied> operations=new LinkedHashMap<>();
        Port(String id,boolean presentationOnly){this.id=id;this.document=NativePackageViews.document(id);this.presentationOnly=presentationOnly;authority=PackageContentClient.session(id);}
        void current(){if(cancelled||controls.get(id)!=this||!NativePackageViews.owns(id)||!document.equals(NativePackageViews.document(id))||!ReadOnlyUiLease.sameContext(authority,PackageContentClient.session(id)))throw new IllegalStateException("USER_INTERRUPTED");}
        @Override public void onInterrupt(Runnable action){interrupt=action;if(cancelled)action.run();}
        @Override public CompletableFuture<String> inspect(){return onClient(()->{current();observation=observe(id);return CompletableFuture.completedFuture(observation.toString());});}
        @Override public CompletableFuture<String> inspectPresentation(){return inspect();}
        @Override public CompletableFuture<UiCapture.Image> capture(){
            if(presentationOnly)return CompletableFuture.failedFuture(new SecurityException("PRESENTATION_ONLY"));
            return inspect().thenCompose(ignored->onClient(()->NativePackageViews.capture(id))).thenApply(shot->{current();image=UiCapture.image(UUID.randomUUID(),shot.documentId(),viewport(),UiCapture.sha256(shot.layoutIdentity().getBytes(StandardCharsets.UTF_8)),shot.frameX(),shot.frameY(),shot.scaleX(),shot.scaleY(),shot.png());deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);return image;});
        }
        String viewport(){return UiCapture.sha256(observation.getAsJsonObject("viewport").toString().getBytes(StandardCharsets.UTF_8));}
        @Override public CompletableFuture<String> act(String encoded){return onClient(()->{
            current();var action=JsonParser.parseString(encoded).getAsJsonObject();String operation=action.get("operationId").getAsString();UUID.fromString(operation);
            var old=operations.get(operation);if(old!=null)return old.action.equals(encoded)?old.result:CompletableFuture.completedFuture("{\"status\":\"OPERATION_ID_REUSED\"}");
            if(operations.size()>=128)return CompletableFuture.completedFuture("{\"status\":\"LEDGER_FULL\"}");var result=new CompletableFuture<String>();operations.put(operation,new Applied(encoded,result));
            try{
                if(observation==null)throw new IllegalStateException("OBSERVE_FIRST");if(presentationOnly&&!action.get("action").getAsString().equals("present"))throw new SecurityException("PRESENTATION_ONLY");
                if(action.get("action").getAsString().equals("clickAt")){coordinate(action).whenComplete((value,error)->{if(error!=null)result.complete(failed(error));else result.complete(value);});return result;}
                if(action.get("action").getAsString().equals("present")){
                    UiPresentationClient.apply(id,"native:"+id,document,observation.getAsJsonObject("hostPresentation"),encoded,()->!cancelled&&controls.get(id)==this&&NativePackageViews.owns(id)&&document.equals(NativePackageViews.document(id))).whenComplete((value,error)->{if(error!=null)result.complete(failed(error));else result.complete(value);});return result;
                }
                image=null;var request=new com.fasterxml.jackson.databind.ObjectMapper().readTree(action.toString());
                result.complete(JSON.toJson(NativePackageViews.act(id,request,observation.get("observationId").getAsString())));
            }catch(Exception error){result.complete(failed(error));}return result;
        });}
        CompletableFuture<String> coordinate(JsonObject action){
            var captured=image;if(captured==null||System.nanoTime()>=deadline)throw new IllegalStateException("STALE_CAPTURE");var manifest=captured.manifest();
            var point=dev.mineagent.runtime.client.webui.UiCaptureCoordinates.point(manifest,UUID.fromString(action.get("captureId").getAsString()),action.get("x").getAsDouble(),action.get("y").getAsDouble(),document,viewport(),UiCapture.sha256(NativePackageViews.identity(id).getBytes(StandardCharsets.UTF_8)));
            JsonObject target=null;for(var entry:observation.getAsJsonArray("elements")){var element=entry.getAsJsonObject();var b=element.getAsJsonObject("bounds");if(element.get("visible").getAsBoolean()&&!element.get("disabled").getAsBoolean()&&!element.get("sensitive").getAsBoolean()&&point.x()>=b.get("x").getAsDouble()&&point.y()>=b.get("y").getAsDouble()&&point.x()<b.get("x").getAsDouble()+b.get("width").getAsDouble()&&point.y()<b.get("y").getAsDouble()+b.get("height").getAsDouble())target=element;}
            if(target==null)throw new IllegalStateException("NOT_INTERACTABLE");var selected=target;var b=target.getAsJsonObject("bounds");var bounds=new dev.mineagent.runtime.client.webui.UiCaptureCoordinates.Bounds(b.get("x").getAsDouble(),b.get("y").getAsDouble(),b.get("width").getAsDouble(),b.get("height").getAsDouble());
            return NativePackageViews.capture(id).thenCompose(fresh->PackageViewCapture.sameTarget(captured.png().bytes(),fresh.png(),manifest,bounds)).thenCompose(same->onClient(()->{
                current();if(!same||image!=captured||System.nanoTime()>=deadline)throw new IllegalStateException("CAPTURE_TARGET_CHANGED");
                var request=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("action","click").put("elementRef",selected.get("elementRef").getAsString());
                var value=new LinkedHashMap<>(NativePackageViews.act(id,request,observation.get("observationId").getAsString()));image=null;value.put("targetPixelsVerified",true);return CompletableFuture.completedFuture(JSON.toJson(value));
            }));
        }
        @Override public void cancel(){if(!Minecraft.getInstance().isSameThread()){Minecraft.getInstance().execute(this::cancel);return;}if(cancelled)return;cancelled=true;image=null;UiPresentationClient.cancel(id);controls.remove(id,this);operations.values().forEach(value->value.result.complete("{\"status\":\"USER_INTERRUPTED\"}"));interrupt.run();}
    }
    private static String failed(Throwable error){while(error.getCause()!=null)error=error.getCause();String code=Objects.toString(error.getMessage(),"FAILED");return JSON.toJson(Map.of("status",code.matches("[A-Z_]{1,80}")?code:"FAILED","businessVerified",false));}
    private static <T> CompletableFuture<T> onClient(Supplier<CompletableFuture<T>> action){var result=new CompletableFuture<T>();Minecraft.getInstance().execute(()->{try{action.get().whenComplete((value,error)->{if(error!=null)result.completeExceptionally(error);else result.complete(value);});}catch(Exception failure){result.completeExceptionally(failure);}});return result;}
}
