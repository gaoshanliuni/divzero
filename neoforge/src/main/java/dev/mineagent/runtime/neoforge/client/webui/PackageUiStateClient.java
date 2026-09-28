package dev.mineagent.runtime.neoforge.client.webui;

import com.google.gson.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.core.ui.PackageUiStateStore;
import dev.mineagent.runtime.neoforge.client.nativeui.NativePackageViews;
import net.minecraft.client.Minecraft;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local JSON state scoped by native document, signed package, server, world and viewer. */
public final class PackageUiStateClient {
    private static final Gson JSON=new Gson();
    private static final ExecutorService IO=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"mineagent-package-ui-state");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final Map<UUID,Flight> pending=new HashMap<>();
    private record Flight(String view,String document,String hash,Session shell,Session source,WebGuiHostAdapter.ViewPackage descriptor,AtomicBoolean valid){}
    private static long rateWindow;private static int rateCount;
    private PackageUiStateClient(){}
    public static void register(){}
    public static void install(String view){}
    public static CompletableFuture<Receipt> request(String view,String action,Map<String,String> args,UUID operation){
        var result=new CompletableFuture<Receipt>();Flight flight=null;
        try{
            var shell=UiClientSessions.current();long now=System.currentTimeMillis();if(now-rateWindow>=1000){rateWindow=now;rateCount=0;}
            if(shell==null||!NativePackageViews.rendered(view)||pending.size()>=8||++rateCount>30)throw new IllegalStateException("UI_STATE_UNAVAILABLE");
            var asset=NativePackageViews.asset(view);var pkg=asset.runtimePackage();var raw=PackageContentClient.rawSession(view);var source=PackageContentClient.session(view);if(raw!=null&&source==null)throw new IllegalStateException("UI_STATE_STALE_VIEW");
            var descriptor=new WebGuiHostAdapter.ViewPackage(pkg.packageId(),pkg.revision(),asset.entry(),NativePackageViews.passive(view));String kind=action.substring("state.".length());boolean write=!kind.equals("get");
            Set<String> allowed=switch(kind){case "get"->Set.of("key");case "put"->Set.of("key","expectedRevision","valueJson");case "remove"->Set.of("key","expectedRevision");default->throw new IllegalArgumentException("UI_STATE_REQUEST");};if(!args.keySet().equals(allowed))throw new IllegalArgumentException("UI_STATE_REQUEST");
            String key=args.get("key");long expected=write?strictRevision(JsonParser.parseString(args.get("expectedRevision"))):0;String value=args.get("valueJson");if(kind.equals("put"))validateValue(value);
            if(write&&(descriptor.passive()||source==null||source.binding().preview()))throw new IllegalStateException("UI_STATE_READ_ONLY");
            flight=new Flight(view,NativePackageViews.document(view),pkg.canonicalSha256(),shell,source,descriptor,new AtomicBoolean(true));final var f=flight;if(pending.putIfAbsent(operation,f)!=null)throw new IllegalStateException("UI_STATE_OPERATION_PENDING");
            var server=Minecraft.getInstance().getCurrentServer();var scope=new PackageUiStateStore.Scope(server==null?"local-integrated":server.ip,shell.binding().worldId(),shell.binding().viewerPlayerId(),descriptor.packageId(),descriptor.entry(),source==null?"":dev.mineagent.runtime.api.ui.WorldUiProtocol.localStateTarget(source.binding()));
            var path=Minecraft.getInstance().gameDirectory.toPath().resolve("mineagent-runtime-data/client-package-ui-state.db");
            (write?permit(f):CompletableFuture.completedFuture(null)).thenCompose(ignored->{if(!current(f))throw new IllegalStateException("UI_STATE_STALE_VIEW");return CompletableFuture.supplyAsync(()->{
                try(var store=new PackageUiStateStore(path,java.time.Clock.systemUTC())){if(!f.valid().get())throw new IllegalStateException("UI_STATE_STALE_VIEW");return switch(kind){case "get"->store.get(scope,key);case "put"->store.put(scope,key,expected,descriptor.revision(),value,f.valid()::get);case "remove"->store.remove(scope,key,expected,descriptor.revision(),f.valid()::get);default->throw new IllegalArgumentException("UI_STATE_REQUEST");};}
                catch(Exception error){throw new CompletionException(error);}
            },IO);}).orTimeout(10,TimeUnit.SECONDS).whenComplete((snapshot,error)->Minecraft.getInstance().execute(()->{
                pending.remove(operation,f);if(error!=null||!current(f)){f.valid().set(false);rejectOrUnknown(result,operation,error==null?"UI_STATE_STALE_VIEW":code(error));}
                else try{var state=JSON.toJsonTree(snapshot).getAsJsonObject();state.remove("valueJson");state.add("value",snapshot.exists()?JsonParser.parseString(snapshot.valueJson()):JsonNull.INSTANCE);String encoded=state.toString();if(encoded.length()>24000){state.remove("value");state.addProperty("valueOmitted",true);result.complete(new Receipt(operation,Code.FAILED,Map.of("state",state.toString(),"errorCode","UI_STATE_VALUE_EXCEEDS_VIEW_LIMIT","localOnly","true")));return;}result.complete(new Receipt(operation,write?Code.APPLIED:Code.OBSERVED,Map.of("state",encoded,"localOnly","true")));}catch(Exception invalid){result.completeExceptionally(invalid);}
            }));
        }catch(Exception error){if(flight!=null){flight.valid().set(false);pending.remove(operation,flight);}rejectOrUnknown(result,operation,code(error));}return result;
    }
    static void validateValue(String value){
        try{if(value==null||value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32768)throw new IllegalArgumentException("UI_STATE_VALUE_BUDGET");var tree=JsonParser.parseString(value);var envelope=new JsonObject();envelope.addProperty("revision",9007199254740991L);envelope.addProperty("exists",true);envelope.addProperty("packageRevision",9007199254740991L);envelope.add("value",tree);if(envelope.toString().length()>24000)throw new IllegalArgumentException("UI_STATE_VALUE_BUDGET");}
        catch(JsonParseException malformed){throw new IllegalArgumentException("UI_STATE_JSON");}
    }
    private static void rejectOrUnknown(CompletableFuture<Receipt> result,UUID operation,String code){
        if(Set.of("UI_STATE_KEY","UI_STATE_SCOPE","UI_STATE_REQUEST","UI_STATE_REVISION","UI_STATE_READ_ONLY","UI_STATE_PERMISSION_DENIED","UI_STATE_CONFLICT","UI_STATE_STALE_PACKAGE","UI_STATE_VALUE_BUDGET","UI_STATE_JSON","UI_STATE_KEY_BUDGET","UI_STATE_TOTAL_BUDGET").contains(code))result.complete(new Receipt(operation,code.equals("UI_STATE_CONFLICT")?Code.STATE_CONFLICT:code.equals("UI_STATE_READ_ONLY")||code.equals("UI_STATE_PERMISSION_DENIED")?Code.PERMISSION_DENIED:Code.INVALID_REQUEST,Map.of("errorCode",code,"notApplied","true","localOnly","true")));
        else result.completeExceptionally(new IllegalStateException(code));
    }
    private static CompletableFuture<Void> permit(Flight f){
        var args=new LinkedHashMap<String,String>();args.put("packageId",f.descriptor().packageId().toString());args.put("packageRevision",Long.toString(f.descriptor().revision()));if(f.source()!=null)args.put("sourceSessionId",f.source().sessionId().toString());
        return UiClientSessions.command("ui.statePermit",args,UUID.randomUUID()).thenApply(r->{if(r.code()!=Code.OBSERVED||!Long.toString(f.descriptor().revision()).equals(r.values().get("packageRevision")))throw new IllegalStateException("UI_STATE_PERMISSION_DENIED");return null;});
    }
    private static boolean current(Flight f){
        if(!f.valid().get()||UiClientSessions.current()!=f.shell()||!NativePackageViews.owns(f.view())||!NativePackageViews.visible(f.view())||!NativePackageViews.document(f.view()).equals(f.document())||!NativePackageViews.asset(f.view()).runtimePackage().canonicalSha256().equals(f.hash()))return false;
        return dev.mineagent.runtime.api.ui.ReadOnlyUiLease.sameContext(PackageContentClient.session(f.view()),f.source())&&(f.source()!=null||PackageContentClient.rawSession(f.view())==null);
    }
    private static long strictRevision(JsonElement value){if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||!value.getAsString().matches("[0-9]{1,16}"))throw new IllegalArgumentException("UI_STATE_REVISION");long revision=value.getAsLong();if(revision>9007199254740991L)throw new IllegalArgumentException("UI_STATE_REVISION");return revision;}
    private static String code(Throwable e){while(e.getCause()!=null)e=e.getCause();String m=e.getMessage();return m!=null&&m.matches("UI_STATE_[A-Z_]{1,64}")?m:"UI_STATE_FAILED";}
    public static void invalidate(String view){pending.values().stream().filter(p->p.view().equals(view)).forEach(p->p.valid().set(false));}
    public static void clear(){pending.values().forEach(p->p.valid().set(false));}
}
