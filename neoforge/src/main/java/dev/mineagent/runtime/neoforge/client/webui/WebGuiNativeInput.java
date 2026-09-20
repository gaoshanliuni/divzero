package dev.mineagent.runtime.neoforge.client.webui;
import com.cinemamod.mcef.MCEF;
import com.google.gson.*;
import dev.mineagent.runtime.client.webui.ManagedInputDispatcher;
import net.minecraft.client.Minecraft;
import org.cef.browser.*;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import java.util.*;
import java.util.concurrent.*;

/** Native input waits for the trusted shell's real hit/focus target. Typed text is never sent in target probes. */
public final class WebGuiNativeInput {
    private static final Gson JSON=new Gson();
    private record Pending(Object browser,String url,CompletableFuture<String> result){}
    private static final Map<UUID,Pending> pending=new HashMap<>();
    private static CefMessageRouter router;
    private static final ManagedInputDispatcher INPUT=new ManagedInputDispatcher(WebGuiNativeInput::resolve,
            id->{UiAgentClient.stop(id,true,"NATIVE_INPUT");PackageContentClient.humanInput(id);ContentTakeoverClient.humanInput(id);ContentHotSwapClient.humanInput(id);},r->Minecraft.getInstance().execute(r),WebGuiNativeInput::failed,128);
    private WebGuiNativeInput(){}
    public static void register(){
        if(router!=null)return;
        router=CefMessageRouter.create(new CefMessageRouter.CefMessageRouterConfig("mineagentInputQuery","mineagentInputQueryCancel"));
        router.addHandler(new CefMessageRouterHandlerAdapter(){
            @Override public boolean onQuery(CefBrowser browser,CefFrame frame,long id,String request,boolean persistent,CefQueryCallback callback){
                if(request==null||persistent||request.length()>2048||!WebGuiHostAdapter.INSTANCE.acceptsTrustedFrame(browser,frame,request.length())){callback.failure(403,"INPUT_TARGET_SOURCE");return true;}
                String url=frame.getURL();
                Minecraft.getInstance().execute(()->{
                    try{
                        var message=JsonParser.parseString(request).getAsJsonObject();UUID query=UUID.fromString(message.get("requestId").getAsString());
                        var p=pending.remove(query);
                        if(p==null||p.browser!=browser||!p.url.equals(url)||!url.equals(browser.getURL())||!WebGuiHostAdapter.INSTANCE.ready()){callback.failure(409,"STALE_INPUT_TARGET");return;}
                        if(message.has("error")){p.result.completeExceptionally(new IllegalStateException("STALE_ATLAS_INPUT"));callback.success("{}");return;}
                        var field=message.get("viewId");if(field==null||!field.isJsonPrimitive()||!field.getAsJsonPrimitive().isString()||field.getAsString().length()>128)throw new IllegalArgumentException("INPUT_TARGET_INVALID");
                        p.result.complete(field.getAsString());callback.success("{}");
                    }catch(RuntimeException invalid){callback.failure(400,"INPUT_TARGET_INVALID");}
                });return true;
            }
        },true);
        MCEF.getClient().getHandle().addMessageRouter(router);
    }
    private static CompletableFuture<String> resolve(ManagedInputDispatcher.TargetRequest request){
        if(WebGuiAtlasCompositor.active()&&(!WebGuiAtlasCompositor.inputAvailable()||!WebGuiAtlasCompositor.inputTokenCurrent(request.atlasToken())))return CompletableFuture.failedFuture(new IllegalStateException("ATLAS_RENDER_ONLY_INPUT_DISABLED"));
        var host=WebGuiHostAdapter.INSTANCE;if(!host.ready()||pending.size()>=8)return CompletableFuture.failedFuture(new IllegalStateException("INPUT_TARGET_UNAVAILABLE"));
        UUID id=UUID.randomUUID();var future=new CompletableFuture<String>();var browser=host.browser();String url=browser.getURL();
        pending.put(id,new Pending(browser,url,future));
        browser.executeJavaScript("window.__mineagentResolveInput("+JSON.toJson(id.toString())+","+JSON.toJson(request)+");",url,0);
        future.orTimeout(750,TimeUnit.MILLISECONDS).whenComplete((v,e)->Minecraft.getInstance().execute(()->pending.remove(id)));
        return future.thenApply(view->{if(!request.atlasToken().isEmpty()&&!WebGuiAtlasCompositor.inputTokenCurrent(request.atlasToken()))throw new IllegalStateException("STALE_ATLAS_INPUT");if(!request.atlasToken().isEmpty()&&request.kind().equals("keyboard")&&!view.isEmpty())WebGuiAtlasCompositor.inputMap().requireInteractive(view);if(request.kind().equals("keyboard"))WebGuiPopupCompositor.noteTarget(view);return view;});
    }
    public static void mapped(double x,double y,dev.mineagent.runtime.client.webui.NativeAtlasInputMap.Point gesture,String kind,java.util.function.Consumer<WebGuiAtlasCompositor.Route> send){
        try{
            var found=WebGuiAtlasCompositor.route(x,y,gesture);
            if(found.isEmpty()){if(kind.equals("motion")){var browser=WebGuiHostAdapter.INSTANCE.browser();INPUT.motion(()->{if(browser!=null&&browser==WebGuiHostAdapter.INSTANCE.browser())browser.sendMouseMove(0,0);});}else failed();return;}
            var route=found.get();Runnable action=()->{if(WebGuiAtlasCompositor.routeCurrent(route))send.accept(route);else if(!kind.equals("motion"))failed();};
            if(kind.equals("motion"))INPUT.motion(route.request(kind),action);else INPUT.input(route.request(kind),action);
        }catch(RuntimeException unavailable){if(!kind.equals("motion"))failed();}
    }
    public static void pointer(double x,double y,Runnable send){var window=Minecraft.getInstance().getWindow();INPUT.input(new ManagedInputDispatcher.TargetRequest("pointer",x,y,window.getWidth(),window.getHeight()),send);}
    public static void keyboard(Runnable send){
        var window=Minecraft.getInstance().getWindow();
        if(WebGuiAtlasCompositor.active()){
            try{var frame=WebGuiAtlasCompositor.inputMap().frame();INPUT.input(new ManagedInputDispatcher.TargetRequest("keyboard",0,0,window.getWidth(),window.getHeight(),frame.token(),"",false),send);}catch(RuntimeException unavailable){failed();}
        }else INPUT.input(new ManagedInputDispatcher.TargetRequest("keyboard",0,0,window.getWidth(),window.getHeight()),send);
    }
    public static void passthrough(Runnable send){INPUT.passthrough(send);}
    public static void motion(Runnable send){INPUT.motion(send);}
    public static boolean afterInputs(Runnable action){return INPUT.afterInputs(action);}
    public static void cancelNative(){INPUT.cancelNativeInputs();}
    public static void clear(){INPUT.cancelNativeInputs();var copy=List.copyOf(pending.values());pending.clear();copy.forEach(p->p.result.completeExceptionally(new IllegalStateException("VIEW_NOT_RENDERED")));}
    private static void failed(){var mc=Minecraft.getInstance();if(mc.screen instanceof WebGuiInteractionScreen screen)screen.releasePressed();
        WebGuiHostAdapter.INSTANCE.emit("sessionError",Map.of("code","UI_INPUT_TARGET_UNAVAILABLE"));}
}
