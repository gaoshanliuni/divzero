package dev.mineagent.runtime.neoforge.client.webui;
import com.cinemamod.mcef.MCEF;
import com.google.gson.*;
import org.cef.browser.*;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import net.minecraft.client.Minecraft;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
/** Dedicated native frame route for local draft data. It never exposes a business-write path. */
public final class PackageFormDrafts {
    private static final Gson JSON=new Gson();private static CefMessageRouter router;
    private record Pending(String view,String url,long frameId,CompletableFuture<JsonObject> future){}
    private static final Map<UUID,Pending> pending=new HashMap<>();
    public static void register(){
        if(router!=null)return;router=CefMessageRouter.create(new CefMessageRouter.CefMessageRouterConfig("mineagentDraftQuery","mineagentDraftQueryCancel"));
        router.addHandler(new CefMessageRouterHandlerAdapter(){
            @Override public boolean onQuery(CefBrowser browser,CefFrame frame,long id,String request,boolean persistent,CefQueryCallback callback){
                if(frame==null||frame.isMain()||!WebGuiHostAdapter.INSTANCE.owns(browser)||request==null||request.length()>65536||persistent){callback.failure(403,"DRAFT_SOURCE_REJECTED");return true;}
                long frameId=frame.getIdentifier();String url=frame.getURL();Minecraft.getInstance().execute(()->{
                    try{var message=JsonParser.parseString(request).getAsJsonObject();UUID requestId=UUID.fromString(message.get("requestId").getAsString());var p=pending.get(requestId);
                        if(p==null||p.frameId()!=frameId||!p.url().equals(url)||!url.equals(WebGuiHostAdapter.INSTANCE.packageUrl(p.view()))){callback.failure(409,"STALE_DRAFT_FRAME");return;}
                        pending.remove(requestId);p.future().complete(message.getAsJsonObject("result"));callback.success("{}");
                    }catch(RuntimeException invalid){callback.failure(400,"DRAFT_INVALID");}
                });return true;
            }
        },true);MCEF.getClient().getHandle().addMessageRouter(router);
    }
    public static CompletableFuture<JsonObject> capture(String view){return query(view,"capture",null);}
    public static CompletableFuture<JsonObject> captureAndSeal(String view){return query(view,"capture",null,true);}
    public static CompletableFuture<JsonObject> restore(String view,JsonObject draft){
        var session=PackageContentClient.session(view);
        if(session==null||!session.binding().capabilities().equals(Set.of("scoreview.read")))return CompletableFuture.failedFuture(new SecurityException("RESTORE_REQUIRES_READ_ONLY_SESSION"));
        return query(view,"restore",draft);
    }
    public static CompletableFuture<JsonObject> restoreDelivery(String view,JsonObject draft,JsonObject expectedCurrent){
        var session=PackageContentClient.session(view);
        if(session==null||!dev.mineagent.runtime.api.ui.DeliveryProtocol.bound(session.binding()))return CompletableFuture.failedFuture(new SecurityException("DELIVERY_DRAFT_SESSION_REQUIRED"));
        dev.mineagent.runtime.api.ui.DeliveryProtocol.deliveryId(session.binding());return query(view,"restore",draft,false,true,expectedCurrent);
    }
    private static CompletableFuture<JsonObject> query(String view,String kind,JsonObject draft){
        return query(view,kind,draft,false);
    }
    private static CompletableFuture<JsonObject> query(String view,String kind,JsonObject draft,boolean freeze){
        return query(view,kind,draft,freeze,false,null);
    }
    private static CompletableFuture<JsonObject> query(String view,String kind,JsonObject draft,boolean freeze,boolean silent,JsonObject expectedCurrent){
        if(pending.size()>=8)return CompletableFuture.failedFuture(new IllegalStateException("DRAFT_QUERY_BUDGET"));
        var host=WebGuiHostAdapter.INSTANCE;String url=host.packageUrl(view);if(url==null||!host.ready())return CompletableFuture.failedFuture(new IllegalStateException("VIEW_NOT_RENDERED"));
        CefFrame frame=null;for(long id:PackagePageAgent.frameIds(host.browser().getFrameIdentifiers())){var f=host.browser().getFrame(id);if(f!=null&&!f.isMain()&&url.equals(f.getURL())){frame=f;break;}}
        if(frame==null)return CompletableFuture.failedFuture(new IllegalStateException("VIEW_NOT_RENDERED"));
        UUID id=UUID.randomUUID();var request=new JsonObject();request.addProperty("kind",kind);request.addProperty("requestId",id.toString());if(draft!=null)request.add("draft",draft);
        request.addProperty("freeze",freeze);
        if(silent)request.addProperty("silent",true);if(expectedCurrent!=null)request.add("expectedCurrent",expectedCurrent);
        var future=new CompletableFuture<JsonObject>();pending.put(id,new Pending(view,url,frame.getIdentifier(),future));
        try(var in=PackageFormDrafts.class.getResourceAsStream("/assets/mineagent_runtime/webui/form-draft.js")){
            if(in==null)throw new IllegalStateException("DRAFT_SCRIPT_MISSING");frame.executeJavaScript(new String(in.readAllBytes(),StandardCharsets.UTF_8)+"("+JSON.toJson(request)+");",url,0);
        }catch(Exception failure){pending.remove(id);future.completeExceptionally(failure);}
        future.orTimeout(10,TimeUnit.SECONDS).whenComplete((v,e)->Minecraft.getInstance().execute(()->pending.remove(id)));return future;
    }
    public static void clear(){var copy=List.copyOf(pending.values());pending.clear();copy.forEach(p->p.future().completeExceptionally(new IllegalStateException("VIEW_NOT_RENDERED")));}
}
