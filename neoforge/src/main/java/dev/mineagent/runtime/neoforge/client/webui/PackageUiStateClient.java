package dev.mineagent.runtime.neoforge.client.webui;
import com.cinemamod.mcef.MCEF;
import com.google.gson.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.core.ui.PackageUiStateStore;
import net.minecraft.client.Minecraft;
import org.cef.browser.*;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local package state, not a world API. Native frame identity supplies every scope field. */
public final class PackageUiStateClient {
    private static final Gson JSON=new Gson();private static CefMessageRouter router;
    private static final ExecutorService IO=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"mineagent-package-ui-state");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final Map<UUID,Flight> pending=new HashMap<>();
    private record Flight(String view,String url,long frame,Session shell,Session source,WebGuiHostAdapter.ViewPackage descriptor,AtomicBoolean valid){}
    private static long rateWindow;private static int rateCount;
    private PackageUiStateClient(){}
    public static void register(){
        if(router!=null)return;router=CefMessageRouter.create(new CefMessageRouter.CefMessageRouterConfig("mineagentStateQuery","mineagentStateQueryCancel"));
        router.addHandler(new CefMessageRouterHandlerAdapter(){
            @Override public boolean onQuery(CefBrowser browser,CefFrame frame,long query,String request,boolean persistent,CefQueryCallback callback){
                if(frame==null||frame.isMain()||persistent||request==null||request.length()>65536||!WebGuiHostAdapter.INSTANCE.owns(browser)){callback.failure(403,"UI_STATE_SOURCE");return true;}
                long id=frame.getIdentifier();String url=frame.getURL();Minecraft.getInstance().execute(()->dispatch(browser,id,url,request,callback));return true;
            }
        },true);MCEF.getClient().getHandle().addMessageRouter(router);
    }
    public static void install(String view){
        var host=WebGuiHostAdapter.INSTANCE;String url=host.packageUrl(view);if(url==null)return;
        try(var stream=PackageUiStateClient.class.getResourceAsStream("/assets/mineagent_runtime/webui/package-state-sdk.js")){
            if(stream==null)throw new IllegalStateException("UI_STATE_SDK_MISSING");String script=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
            for(long id:host.browser().getFrameIdentifiers()){var f=host.browser().getFrame(id);if(f!=null&&!f.isMain()&&url.equals(f.getURL())){f.executeJavaScript(script,url,0);return;}}
        }catch(Exception failure){host.emit("contentError",Map.of("viewId",view,"code","UI_STATE_SDK_FAILED"));}
    }
    private static void dispatch(CefBrowser browser,long frame,String url,String encoded,CefQueryCallback callback){
        UUID id=UUID.randomUUID();Flight flight=null;
        try{
            var host=WebGuiHostAdapter.INSTANCE;var shell=UiClientSessions.current();String view=host.viewForPackageUrl(url);
            long now=System.currentTimeMillis();if(now-rateWindow>=1000){rateWindow=now;rateCount=0;}
            if(view==null||shell==null||!host.packageLoaded(view)||host.packageHidden(view)||pending.size()>=8||++rateCount>30)throw new IllegalStateException("UI_STATE_UNAVAILABLE");
            var descriptor=host.viewPackage(view);var raw=PackageContentClient.rawSession(view);var source=PackageContentClient.session(view);
            if(raw!=null&&source==null)throw new IllegalStateException("UI_STATE_STALE_VIEW");
            var data=JsonParser.parseString(encoded).getAsJsonObject();String kind=data.get("kind").getAsString();String key=data.get("key").getAsString();boolean write=!kind.equals("get");
            Set<String> allowed=kind.equals("get")?Set.of("kind","key"):kind.equals("put")?Set.of("kind","key","expectedRevision","valueJson"):kind.equals("remove")?Set.of("kind","key","expectedRevision"):Set.of();
            if(!data.keySet().equals(allowed)||!data.get("kind").getAsJsonPrimitive().isString()||!data.get("key").getAsJsonPrimitive().isString())throw new IllegalArgumentException("UI_STATE_REQUEST");
            long expected=write?strictRevision(data.get("expectedRevision")):0;String value=kind.equals("put")?data.get("valueJson").getAsString():null;
            if(kind.equals("put")&&!data.get("valueJson").getAsJsonPrimitive().isString())throw new IllegalArgumentException("UI_STATE_JSON");
            if(write&&descriptor.passive())throw new IllegalStateException("UI_STATE_READ_ONLY");
            flight=new Flight(view,url,frame,shell,source,descriptor,new AtomicBoolean(true));pending.put(id,flight);final var f=flight;
            var server=Minecraft.getInstance().getCurrentServer();var scope=new PackageUiStateStore.Scope(server==null?"local-integrated":server.ip,shell.binding().worldId(),shell.binding().viewerPlayerId(),descriptor.packageId(),descriptor.entry(),source==null?"":dev.mineagent.runtime.api.ui.WorldUiProtocol.localStateTarget(source.binding()));
            var path=Minecraft.getInstance().gameDirectory.toPath().resolve("mineagent-runtime-data/client-package-ui-state.db");
            var permit=write?permit(f):CompletableFuture.completedFuture(null);
            permit.thenCompose(ignored->{if(!current(f,browser))throw new IllegalStateException("UI_STATE_STALE_VIEW");return CompletableFuture.supplyAsync(()->{
                try(var store=new PackageUiStateStore(path,java.time.Clock.systemUTC())){
                    if(!f.valid().get())throw new IllegalStateException("UI_STATE_STALE_VIEW");
                    return switch(kind){case "get"->store.get(scope,key);case "put"->store.put(scope,key,expected,descriptor.revision(),value,f.valid()::get);case "remove"->store.remove(scope,key,expected,descriptor.revision(),f.valid()::get);default->throw new IllegalArgumentException("UI_STATE_REQUEST");};
                }catch(Exception e){throw new CompletionException(e);}
            },IO);}).orTimeout(10,TimeUnit.SECONDS).whenComplete((snapshot,error)->Minecraft.getInstance().execute(()->{
                pending.remove(id);if(error!=null){f.valid().set(false);callback.failure(409,code(error));}
                else if(!current(f,browser)){f.valid().set(false);callback.failure(409,"UI_STATE_STALE_VIEW");}
                else callback.success(JSON.toJson(Map.of("code","OK","snapshot",snapshot)));
            }));
        }catch(Exception error){if(flight!=null)flight.valid().set(false);pending.remove(id);callback.failure(409,code(error));}
    }
    private static CompletableFuture<Void> permit(Flight f){
        var args=new LinkedHashMap<String,String>();args.put("packageId",f.descriptor().packageId().toString());args.put("packageRevision",Long.toString(f.descriptor().revision()));if(f.source()!=null)args.put("sourceSessionId",f.source().sessionId().toString());
        return UiClientSessions.command("ui.statePermit",args,UUID.randomUUID()).thenApply(r->{if(r.code()!=Code.OBSERVED||!Long.toString(f.descriptor().revision()).equals(r.values().get("packageRevision")))throw new IllegalStateException("UI_STATE_PERMISSION_DENIED");return null;});
    }
    private static boolean current(Flight f,CefBrowser browser){
        var host=WebGuiHostAdapter.INSTANCE;if(!f.valid().get()||!host.owns(browser)||UiClientSessions.current()!=f.shell()||!Objects.equals(host.viewPackage(f.view()),f.descriptor())||!Objects.equals(host.packageUrl(f.view()),f.url())||host.packageHidden(f.view()))return false;
        var frame=browser.getFrame(f.frame());return frame!=null&&!frame.isMain()&&f.url().equals(frame.getURL())&&dev.mineagent.runtime.api.ui.ReadOnlyUiLease.sameContext(PackageContentClient.session(f.view()),f.source())&&(f.source()!=null||PackageContentClient.rawSession(f.view())==null);
    }
    private static long strictRevision(JsonElement value){if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||!value.getAsString().matches("[0-9]{1,16}"))throw new IllegalArgumentException("UI_STATE_REVISION");long revision=value.getAsLong();if(revision>9007199254740991L)throw new IllegalArgumentException("UI_STATE_REVISION");return revision;}
    private static String code(Throwable e){while(e.getCause()!=null)e=e.getCause();String m=e.getMessage();return m!=null&&m.matches("UI_STATE_[A-Z_]{1,64}")?m:"UI_STATE_FAILED";}
    public static void invalidate(String view){pending.values().stream().filter(p->p.view().equals(view)).forEach(p->p.valid().set(false));}
    public static void clear(){pending.values().forEach(p->p.valid().set(false));}
}
