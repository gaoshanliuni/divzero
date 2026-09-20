package dev.mineagent.runtime.neoforge.client.webui;

import com.cinemamod.mcef.MCEF;
import com.google.gson.*;
import dev.mineagent.runtime.agent.ui.UiAgentController;
import net.minecraft.client.Minecraft;
import org.cef.browser.*;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Exact native frame routing for package previews only. Trusted shell/authorization cards are never valid targets. */
public final class PackagePageAgent {
    private static final Gson JSON = new Gson();
    private static final String SDK = loadSdk();
    private static CefMessageRouter router;
    private static final Map<String, Port> controls = new HashMap<>();
    private static final Map<String, String> scopeKeys = new HashMap<>();
    private static final Map<String, Pending> pending = new HashMap<>();
    private record Pending(Port port, long frameId, CompletableFuture<String> future) {}
    private PackagePageAgent() {}
    public static void register() {
        if (router != null) return;
        router = CefMessageRouter.create(new CefMessageRouter.CefMessageRouterConfig("mineagentPageAgentQuery", "mineagentPageAgentQueryCancel"));
        router.addHandler(new CefMessageRouterHandlerAdapter() {
            @Override public boolean onQuery(CefBrowser browser, CefFrame frame, long id, String request, boolean persistent, CefQueryCallback callback) {
                if (!WebGuiHostAdapter.INSTANCE.owns(browser) || frame == null || frame.isMain() || request == null || request.length() > 65_536 || persistent) {
                    callback.failure(403,"UNAUTHORIZED_FRAME"); return true;
                }
                long frameId=frame.getIdentifier(); String url=frame.getURL();
                Minecraft.getInstance().execute(() -> {
                    try {
                        JsonObject message=JsonParser.parseString(request).getAsJsonObject();
                        if (message.has("event") && message.get("event").getAsString().equals("interrupt")) {
                            controls.values().stream().filter(p -> p.url.equals(url)).toList().forEach(Port::cancel);
                            callback.success("{}"); return;
                        }
                        String requestId=message.get("requestId").getAsString();
                        Pending p=pending.remove(requestId);
                        if (p==null || p.frameId!=frameId || p.port.cancelled || !p.port.url.equals(url)
                                || controls.get(p.port.viewId)!=p.port) { callback.failure(409,"STALE_VIEW"); return; }
                        p.future.complete(message.getAsJsonObject("result").toString()); callback.success("{}");
                    } catch (RuntimeException invalid) { callback.failure(400,"INVALID_PAGE_RECEIPT"); }
                }); return true;
            }
        },true);
        MCEF.getClient().getHandle().addMessageRouter(router);
    }
    public static UiAgentController.Port controlPreview(String viewId) {
        requireClient();
        if (PackageContentClient.owns(viewId)) throw new SecurityException("CONTENT_AUTOMATION_REQUIRES_AGENT_DELEGATION");
        return control(viewId);
    }
    public static UiAgentController.Port controlDelegated(String viewId,UUID sessionId){return controlDelegated(viewId,sessionId,false);}
    public static UiAgentController.Port controlDelegated(String viewId,UUID sessionId,boolean presentationOnly){
        if(WebGuiAtlasCompositor.active()&&!WebGuiAtlasCompositor.inputAvailable())throw new SecurityException("ATLAS_AGENT_INPUT_NOT_AVAILABLE");
        requireClient();var session=PackageContentClient.session(viewId);
        if(session==null||session.binding().actorKind()!=dev.mineagent.runtime.api.ui.UiProtocol.ActorKind.AGENT||!session.sessionId().equals(sessionId))throw new SecurityException("CONTENT_AGENT_SESSION_REQUIRED");
        return control(viewId,presentationOnly);
    }
    private static UiAgentController.Port control(String viewId){return control(viewId,false);}
    private static UiAgentController.Port control(String viewId,boolean presentationOnly){
        if(WebGuiAtlasCompositor.active()&&!WebGuiAtlasCompositor.inputAvailable())throw new SecurityException("ATLAS_AGENT_INPUT_NOT_AVAILABLE");
        if(WebGuiAtlasCompositor.active()){if(presentationOnly)WebGuiAtlasCompositor.inputMap().identity(viewId,false);else WebGuiAtlasCompositor.inputMap().requireInteractive(viewId);}
        String url=WebGuiHostAdapter.INSTANCE.packageUrl(viewId);
        if (url==null) throw new IllegalArgumentException("NOT_A_PACKAGE_PREVIEW");
        if (controls.containsKey(viewId)) throw new IllegalStateException("UI_CONTROL_BUSY");
        Port port=new Port(viewId,url,false,presentationOnly); controls.put(viewId,port);
        WebGuiHostAdapter.INSTANCE.emit("previewAgentStatus",Map.of("viewId",viewId,"running",true));
        port.ready=port.query("reset",null); return port;
    }
    /** Native read-only observer; never returns an act-capable Port or grants an Agent the PLAYER binding. */
    public static CompletableFuture<String> inspectManagedView(String viewId) {
        requireClient();
        String url=WebGuiHostAdapter.INSTANCE.packageUrl(viewId);
        if(url==null)return CompletableFuture.failedFuture(new IllegalArgumentException("NOT_A_MANAGED_VIEW"));
        if(controls.containsKey(viewId))return CompletableFuture.failedFuture(new IllegalStateException("UI_CONTROL_BUSY"));
        Port port=new Port(viewId,url,true);controls.put(viewId,port);port.ready=port.query("reset",null);
        return port.inspect().whenComplete((value,error)->port.cancel());
    }
    /** Native read-only capture; acquiring this observer never grants a business-write Port. */
    public static CompletableFuture<PackageViewCapture.Captured> captureManagedView(String viewId){
        requireClient();String url=WebGuiHostAdapter.INSTANCE.packageUrl(viewId);
        if(url==null||!(Minecraft.getInstance().screen instanceof WebGuiInteractionScreen))return CompletableFuture.failedFuture(new IllegalStateException("VIEW_NOT_RENDERED"));
        if(controls.containsKey(viewId))return CompletableFuture.failedFuture(new IllegalStateException("UI_CONTROL_BUSY"));
        Port port=new Port(viewId,url,true);controls.put(viewId,port);port.ready=port.query("reset",null);
        return port.inspect().thenCompose(ignored->port.capturePixels()).whenComplete((value,error)->port.cancel());
    }
    public static void cancel(String viewId) { requireClient(); Port port=controls.get(viewId); if(port!=null) port.cancel(); }
    static void interruptControls() { requireClient(); new ArrayList<>(controls.values()).forEach(Port::cancel); }
    public static void clear() { interruptControls(); scopeKeys.clear(); }
    private static final class Port implements UiAgentController.Port {
        final String viewId,url;final boolean readOnly,presentationOnly; boolean cancelled;
        CompletableFuture<String> ready;
        JsonObject observation;
        private record CoordinateCapture(dev.mineagent.runtime.api.ui.UiCapture.Image image,long deadline,String atlasIdentity){}
        private record CoordinateReceipt(String fingerprint,CompletableFuture<String> result){}
        CoordinateCapture coordinateCapture;
        final Map<String,CoordinateReceipt> coordinateLedger=new LinkedHashMap<>();
        Runnable interruption=()->{};
        Port(String viewId,String url) { this(viewId,url,false); }
        Port(String viewId,String url,boolean readOnly) { this(viewId,url,readOnly,false); }
        Port(String viewId,String url,boolean readOnly,boolean presentationOnly) { this.viewId=viewId; this.url=url;this.readOnly=readOnly;this.presentationOnly=presentationOnly; }
        @Override public void onInterrupt(Runnable listener) { interruption=listener; if(cancelled)listener.run(); }
        @Override public CompletableFuture<String> inspect() {
            if(presentationOnly)return inspectPresentation();
            return ready.thenCompose(ignored -> onClient(() -> query("inspect",null))).thenApply(value -> {
                JsonObject result=JsonParser.parseString(value).getAsJsonObject();
                if(!result.get("status").getAsString().equals("OBSERVED")) throw new IllegalStateException(result.get("status").getAsString());
                if(WebGuiAtlasCompositor.active())result.addProperty("atlasIdentity",WebGuiAtlasCompositor.captureIdentity(viewId,!readOnly));
                var presentation=UiPresentationClient.observe(viewId);if(presentation!=null)result.add("hostPresentation",presentation);observation=result; return result.toString();
            });
        }
        @Override public CompletableFuture<String> inspectPresentation(){
            return ready.thenCompose(ignored->onClient(()->query("identity",null))).thenApply(value->{var n=JsonParser.parseString(value).getAsJsonObject();if(!n.get("status").getAsString().equals("OBSERVED"))throw new IllegalStateException("VIEW_NOT_RENDERED");var presentation=UiPresentationClient.observe(viewId);if(presentation!=null)n.add("hostPresentation",presentation);observation=n;return n.toString();});
        }
        @Override public CompletableFuture<String> act(String encoded) {
            return onClient(() -> {
                if(WebGuiAtlasCompositor.active()&&!WebGuiAtlasCompositor.inputAvailable())throw new SecurityException("ATLAS_AGENT_INPUT_NOT_AVAILABLE");
                JsonObject action=JsonParser.parseString(encoded).getAsJsonObject();
                if(observation==null) throw new IllegalStateException("OBSERVE_FIRST");
                if(presentationOnly&&!action.get("action").getAsString().equals("present"))throw new SecurityException("PRESENTATION_ONLY");
                if(WebGuiAtlasCompositor.active()&&!action.get("action").getAsString().equals("present")&&(!observation.has("atlasIdentity")||!WebGuiAtlasCompositor.captureCurrent(viewId,observation.get("atlasIdentity").getAsString(),true)))throw new IllegalStateException("STALE_LAYOUT");
                if(action.get("action").getAsString().equals("present")){
                    coordinateCapture=null;var expected=observation.deepCopy();var captured=findFrame(url);if(captured==null)return CompletableFuture.failedFuture(new IllegalStateException("VIEW_NOT_RENDERED"));long frameId=captured.getIdentifier();
                    return query("identity",null).thenCompose(value->{var fresh=JsonParser.parseString(value).getAsJsonObject();if(!fresh.get("documentId").equals(expected.get("documentId")))return CompletableFuture.failedFuture(new IllegalStateException("STALE_VIEW"));return UiPresentationClient.apply(viewId,url,expected.get("documentId").getAsString(),expected.has("hostPresentation")?expected.getAsJsonObject("hostPresentation"):null,encoded,()->!cancelled&&controls.get(viewId)==this&&findFrame(url)!=null&&findFrame(url).getIdentifier()==frameId);});
                }
                if(action.get("action").getAsString().equals("clickAt"))return clickAt(action);
                coordinateCapture=null;
                action.add("documentId",observation.get("documentId")); action.add("observationId",observation.get("observationId"));
                return query("act",action);
            });
        }
        @Override public CompletableFuture<dev.mineagent.runtime.api.ui.UiCapture.Image> capture(){
            if(presentationOnly)return CompletableFuture.failedFuture(new SecurityException("PRESENTATION_ONLY"));
            UUID id=UUID.randomUUID();coordinateCapture=null;
            return inspect().thenCompose(ignored->{
                var seal=new JsonObject();seal.addProperty("captureId",id.toString());seal.add("documentId",observation.get("documentId"));seal.add("observationId",observation.get("observationId"));
                return onClient(()->query("sealCapture",seal));
            }).thenCompose(sealed->{
                if(!JsonParser.parseString(sealed).getAsJsonObject().get("status").getAsString().equals("CAPTURE_SEALED"))throw new IllegalStateException("STALE_CAPTURE");
                return capturePixels();
            }).thenApply(shot->{
                var image=dev.mineagent.runtime.api.ui.UiCapture.image(id,shot.documentId(),viewportHash(),
                        dev.mineagent.runtime.api.ui.UiCapture.sha256(shot.layoutIdentity().getBytes(StandardCharsets.UTF_8)),shot.frameX(),shot.frameY(),shot.scaleX(),shot.scaleY(),shot.png());
                coordinateCapture=new CoordinateCapture(image,System.nanoTime()+TimeUnit.SECONDS.toNanos(120),WebGuiAtlasCompositor.captureIdentity(viewId,true));return image;
            });
        }
        String viewportHash(){return dev.mineagent.runtime.api.ui.UiCapture.sha256(observation.getAsJsonObject("viewport").toString().getBytes(StandardCharsets.UTF_8));}
        CompletableFuture<String> clickAt(JsonObject requested){
            String operation=requested.get("operationId").getAsString(),fingerprint=requested.toString();
            var old=coordinateLedger.get(operation);if(old!=null)return old.fingerprint.equals(fingerprint)?old.result:CompletableFuture.completedFuture("{\"status\":\"OPERATION_ID_REUSED\"}");
            if(coordinateLedger.size()>=128)return CompletableFuture.completedFuture("{\"status\":\"LEDGER_FULL\"}");
            var result=new CompletableFuture<String>();coordinateLedger.put(operation,new CoordinateReceipt(fingerprint,result));
            try{
                var captured=coordinateCapture;if(captured==null||System.nanoTime()>=captured.deadline)throw new IllegalStateException("STALE_CAPTURE");
                var manifest=captured.image.manifest();UUID id=UUID.fromString(requested.get("captureId").getAsString());
                if(!requested.get("x").getAsJsonPrimitive().isNumber()||!requested.get("y").getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("CAPTURE_COORDINATE_BOUNDS");
                double x=requested.get("x").getAsDouble(),y=requested.get("y").getAsDouble();
                var point=dev.mineagent.runtime.client.webui.UiCaptureCoordinates.point(manifest,id,x,y,observation.get("documentId").getAsString(),viewportHash(),manifest.layoutHash());
                var action=new JsonObject();action.addProperty("action","clickAt");action.addProperty("operationId",operation);action.addProperty("captureId",id.toString());action.addProperty("documentId",manifest.documentId());action.addProperty("pageX",point.x());action.addProperty("pageY",point.y());
                query("prepareClickAt",action).thenCompose(prepared->{
                    var target=JsonParser.parseString(prepared).getAsJsonObject();String status=target.get("status").getAsString();
                    if(!status.equals("CAPTURE_TARGET"))return CompletableFuture.<String>completedFuture(prepared);
                    var b=target.getAsJsonObject("bounds");var bounds=new dev.mineagent.runtime.client.webui.UiCaptureCoordinates.Bounds(b.get("x").getAsDouble(),b.get("y").getAsDouble(),b.get("width").getAsDouble(),b.get("height").getAsDouble());
                    return capturePixels().thenCompose(fresh->{
                        requireCapture(captured);
                        dev.mineagent.runtime.client.webui.UiCaptureCoordinates.point(manifest,id,x,y,fresh.documentId(),viewportHash(),dev.mineagent.runtime.api.ui.UiCapture.sha256(fresh.layoutIdentity().getBytes(StandardCharsets.UTF_8)));
                        if(fresh.width()!=manifest.width()||fresh.height()!=manifest.height()||fresh.scaleX()!=manifest.scaleX()||fresh.scaleY()!=manifest.scaleY()||fresh.frameX()!=manifest.frameX()||fresh.frameY()!=manifest.frameY())throw new IllegalStateException("STALE_CAPTURE");
                        return PackageViewCapture.sameTarget(captured.image.png().bytes(),fresh.png(),manifest,bounds).thenCompose(same->onClient(()->{
                            if(!same)throw new IllegalStateException("CAPTURE_TARGET_CHANGED");
                            var dispatched=new CompletableFuture<String>();
                            if(!WebGuiNativeInput.afterInputs(()->{
                                try{requireCapture(captured);query("act",action).whenComplete((receipt,error)->{if(error!=null)dispatched.completeExceptionally(error);else dispatched.complete(receipt);});}
                                catch(Exception invalid){dispatched.completeExceptionally(invalid);}
                            }))throw new IllegalStateException("UI_INPUT_QUEUE_BUSY");
                            return dispatched.thenApply(receipt->{
                                coordinateCapture=null;var r=JsonParser.parseString(receipt).getAsJsonObject();r.addProperty("imageX",x);r.addProperty("imageY",y);r.addProperty("targetPixelsVerified",true);r.addProperty("businessVerified",false);return r.toString();
                            });
                        }));
                    });
                }).whenComplete((receipt,error)->{if(error!=null)result.complete(coordinateFailure(operation,error));else result.complete(receipt);});
            }catch(Exception invalid){result.complete(coordinateFailure(operation,invalid));}
            return result;
        }
        void requireCapture(CoordinateCapture expected){if(cancelled||controls.get(viewId)!=this||coordinateCapture!=expected||System.nanoTime()>=expected.deadline||!WebGuiAtlasCompositor.captureCurrent(viewId,expected.atlasIdentity(),true))throw new IllegalStateException("STALE_CAPTURE");}
        String coordinateFailure(String operation,Throwable error){
            for(int i=0;i<8&&error.getCause()!=null;i++)error=error.getCause();String code=error.getMessage();
            if(code==null||!Set.of("STALE_CAPTURE","CAPTURE_TARGET_CHANGED","CAPTURE_COORDINATE_BOUNDS","VIEW_OCCLUDED","VIEW_POPUP_ACTIVE","VIEW_NOT_RENDERED","USER_INTERRUPTED","CAPTURE_BUSY","CAPTURE_PIXEL_BUDGET","UI_INPUT_QUEUE_BUSY").contains(code))code="FAILED";
            return JSON.toJson(Map.of("status",code,"operationId",operation));
        }
        CompletableFuture<PackageViewCapture.Captured> capturePixels(){
            return onClient(()->{
                if(observation==null)throw new IllegalStateException("OBSERVE_FIRST");
                var atlasIdentity=WebGuiAtlasCompositor.captureIdentity(viewId,!readOnly);
                var expected=observation.deepCopy();var session=PackageContentClient.rawSession(viewId);var browser=WebGuiHostAdapter.INSTANCE.browser();
                var frame=findFrame(url);if(frame==null)throw new IllegalStateException("VIEW_NOT_RENDERED");long frameId=frame.getIdentifier();
                java.util.function.BooleanSupplier current=()->!cancelled&&controls.get(viewId)==this&&WebGuiAtlasCompositor.captureCurrent(viewId,atlasIdentity,!readOnly)&&WebGuiHostAdapter.INSTANCE.browser()==browser
                        &&url.equals(WebGuiHostAdapter.INSTANCE.packageUrl(viewId))&&dev.mineagent.runtime.api.ui.ReadOnlyUiLease.sameContext(session,PackageContentClient.rawSession(viewId))
                        &&Minecraft.getInstance().screen instanceof WebGuiInteractionScreen&&findFrame(url)!=null&&findFrame(url).getIdentifier()==frameId;
                return PackageViewCapture.capture(viewId,expected.toString(),current).thenCompose(shot->inspect().thenApply(value->{
                    var after=JsonParser.parseString(value).getAsJsonObject();
                    if(!current.getAsBoolean()||!expected.get("documentId").equals(after.get("documentId"))||!expected.get("viewport").equals(after.get("viewport")))throw new IllegalStateException("STALE_VIEW");
                    return atlasIdentity.isEmpty()?shot:new PackageViewCapture.Captured(shot.documentId(),shot.layoutIdentity()+"|atlas:"+atlasIdentity,shot.width(),shot.height(),shot.frameX(),shot.frameY(),shot.scaleX(),shot.scaleY(),shot.png());
                }));
            });
        }
        CompletableFuture<String> query(String kind,JsonObject action) {
            if(cancelled || controls.get(viewId)!=this || !url.equals(WebGuiHostAdapter.INSTANCE.packageUrl(viewId)))
                return CompletableFuture.failedFuture(new IllegalStateException("USER_INTERRUPTED"));
            CefFrame frame=findFrame(url);
            if(frame==null) return CompletableFuture.failedFuture(new IllegalStateException("VIEW_NOT_RENDERED"));
            String requestId=UUID.randomUUID().toString();
            var value=new JsonObject(); value.addProperty("requestId",requestId); value.addProperty("kind",kind);
            if(action!=null) value.add("action",action);
            var future=new CompletableFuture<String>(); pending.put(requestId,new Pending(this,frame.getIdentifier(),future));
            String scope=scopeKeys.computeIfAbsent(viewId,ignored -> "__mineagent_"+UUID.randomUUID().toString().replace("-",""));
            frame.executeJavaScript(SDK+"("+JSON.toJson(scope)+","+value+");",url,0);
            future.orTimeout(10,TimeUnit.SECONDS).whenComplete((r,e) -> Minecraft.getInstance().execute(() -> pending.remove(requestId)));
            return future;
        }
        @Override public void cancel() {
            if(!Minecraft.getInstance().isSameThread()) { Minecraft.getInstance().execute(this::cancel); return; }
            if(cancelled) return; cancelled=true;coordinateCapture=null; controls.remove(viewId,this);
            UiPresentationClient.cancel(viewId);
            coordinateLedger.values().forEach(r->r.result.completeExceptionally(new IllegalStateException("USER_INTERRUPTED")));coordinateLedger.clear();
            interruption.run();
            var ids=pending.entrySet().stream().filter(e -> e.getValue().port==this).map(Map.Entry::getKey).toList();
            for(String id:ids) { Pending p=pending.remove(id); if(p!=null)p.future.completeExceptionally(new IllegalStateException("USER_INTERRUPTED")); }
            // Java authority and pending futures are already revoked above. A browser
            // retiring during logout may no longer have frames to notify.
            try{
                CefFrame frame=findFrame(url); String scope=scopeKeys.get(viewId);
                if(frame!=null && scope!=null) frame.executeJavaScript(SDK+"("+JSON.toJson(scope)+",{kind:'cancel'});",url,0);
                WebGuiHostAdapter.INSTANCE.emit("previewAgentStatus",Map.of("viewId",viewId,"running",false));
            }catch(RuntimeException retired){/* Best-effort notification must not abort Native teardown. */}
        }
    }
    private static CefFrame findFrame(String url) {
        var browser=WebGuiHostAdapter.INSTANCE.browser(); if(browser==null) return null;
        for(long id:frameIds(browser.getFrameIdentifiers())) { CefFrame frame=browser.getFrame(id); if(frame!=null && !frame.isMain() && url.equals(frame.getURL())) return frame; }
        return null;
    }
    static List<Long> frameIds(Collection<Long> nativeIds){return nativeIds==null?List.of():List.copyOf(nativeIds);}
    private static <T> CompletableFuture<T> onClient(Supplier<CompletableFuture<T>> action) {
        var future=new CompletableFuture<T>();
        Minecraft.getInstance().execute(() -> { try { action.get().whenComplete((v,e)->{if(e!=null)future.completeExceptionally(e);else future.complete(v);}); }
            catch(Exception failure){future.completeExceptionally(failure);} });
        return future;
    }
    private static void requireClient() { if(!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("CLIENT_THREAD_REQUIRED"); }
    private static String loadSdk() {
        try(var in=PackagePageAgent.class.getResourceAsStream("/assets/mineagent_runtime/webui/page-agent.js")) {
            if(in==null)throw new IllegalStateException("PAGE_AGENT_SDK_MISSING"); return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        } catch(Exception e){throw new IllegalStateException(e);}
    }
}
