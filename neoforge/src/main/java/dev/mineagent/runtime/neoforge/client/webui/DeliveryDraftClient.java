package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.api.ui.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.client.webui.*;
import dev.mineagent.runtime.client.trust.ServerTrustStore;
import net.minecraft.client.Minecraft;
import java.util.*;
import java.util.concurrent.*;

/** Local ordinary-form drafts. Restoring requires an explicit trusted-host action on a newly admitted delivery. */
public final class DeliveryDraftClient {
    private static final Gson JSON=new Gson();private static final Map<String,State> states=new HashMap<>();
    private static final ExecutorService IO=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"mineagent-delivery-drafts");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final class State{
        final String view;final DeliveryDraftScope scope;boolean loaded,closed,restoring,sessionOnly;long epoch;int after;String status="LOADING",diagnostic="",lastStored="",lastNotice="";JsonObject candidate;CompletableFuture<Void> saving;
        State(String view,DeliveryDraftScope scope){this.view=view;this.scope=scope;}
    }
    private DeliveryDraftClient(){}
    static boolean acceptsInitialLoad(long epoch,boolean closed,boolean sessionOnly){return epoch==0&&!closed&&!sessionOnly;}
    private static UiStateStore store(){return new UiStateStore(Minecraft.getInstance().gameDirectory.toPath().resolve("mineagent-runtime-data/delivery-drafts"));}
    private static void captured(JsonObject draft){if(draft==null||!draft.has("status")||!draft.get("status").getAsString().equals("DRAFT_CAPTURED")||draft.toString().length()>49152)throw new IllegalStateException("DELIVERY_DRAFT_CAPTURE_INVALID");}
    private static boolean current(State s,Session context,long life){return !s.closed&&states.get(s.view)==s&&Objects.equals(context,PackageContentClient.session(s.view))&&life==PackageContentClient.lifecycle(s.view);}
    public static void mounted(DeliveryProtocol.Launch launch,Session session){
        if(!launch.mode().equals("CONTENT"))return;
        try{
            var mc=Minecraft.getInstance();String server=mc.getCurrentServer()==null?"local-integrated":mc.getCurrentServer().ip;
            String fingerprint=new ServerTrustStore(mc.gameDirectory.toPath().resolve("config/mineagent-trusted-servers.properties")).trustedFingerprint(server);
            var scope=DeliveryDraftScope.of(server,fingerprint,session,launch.canonicalSha256());var s=new State(launch.viewId(),scope);if(states.size()>=32)throw new IllegalStateException("DELIVERY_DRAFT_VIEW_BUDGET");states.put(s.view,s);
            CompletableFuture.supplyAsync(()->{try{return store().load(scope.storageKey());}catch(Exception e){throw new CompletionException(e);}},IO).whenComplete((text,error)->mc.execute(()->{
                if(!acceptsInitialLoad(s.epoch,s.closed,s.sessionOnly)||states.get(s.view)!=s)return;s.loaded=true;
                try{if(error!=null)throw new IllegalStateException("DELIVERY_DRAFT_LOAD_FAILED");var n=JsonParser.parseString(text).getAsJsonObject();
                    if(!n.isEmpty()){if(!scope.equals(JSON.fromJson(n.get("scope"),DeliveryDraftScope.class)))throw new SecurityException("DELIVERY_DRAFT_SCOPE_CHANGED");var draft=n.getAsJsonObject("draft");captured(draft);s.candidate=draft.deepCopy();s.lastStored=draft.toString();s.status="AVAILABLE";}else s.status="READY";
                }catch(Exception failed){s.status="FAILED";s.diagnostic=failed.getMessage();}emit(s,true);
            }));
        }catch(Exception failed){WebGuiHostAdapter.INSTANCE.emit("contentError",Map.of("viewId",launch.viewId(),"code","DELIVERY_DRAFT_INITIALIZATION_FAILED"));}
    }
    public static void ready(String view){var s=states.get(view);if(s!=null)emit(s,true);}
    public static String status(String view){var s=states.get(view);return s==null?"NO_DRAFT_CONTEXT":s.status;}
    public static boolean maySubmit(String view){var s=states.get(view);return s==null||s.loaded&&!s.restoring&&s.candidate==null&&!s.status.equals("FAILED");}
    private static void emit(State s,boolean force){if(s.closed)return;String notice=s.status+"|"+s.diagnostic+"|"+(s.candidate!=null);if(!force&&notice.equals(s.lastNotice))return;s.lastNotice=notice;WebGuiHostAdapter.INSTANCE.emit("deliveryDraftStatus",Map.of("viewId",s.view,"status",s.status,"restoreAvailable",s.candidate!=null,"diagnostic",Objects.toString(s.diagnostic,"")));}
    public static CompletableFuture<Void> beforeSubmit(String view){
        var s=states.get(view);if(s==null||s.sessionOnly)return CompletableFuture.completedFuture(null);if(!maySubmit(view))return CompletableFuture.failedFuture(new IllegalStateException("DELIVERY_DRAFT_CHOICE_OR_RESTORE_PENDING"));
        if(s.saving!=null){var prior=s.saving;return prior.thenCompose(ignored->{if(s.saving==prior)s.saving=null;return save(s,true);});}return save(s,true);
    }
    private static CompletableFuture<Void> save(State s,boolean force){
        var context=PackageContentClient.session(s.view);long life=PackageContentClient.lifecycle(s.view),epoch=s.epoch;if(context==null||s.closed||s.restoring||s.candidate!=null||s.sessionOnly)return CompletableFuture.failedFuture(new IllegalStateException("DELIVERY_DRAFT_NOT_READY"));
        if(s.saving!=null)return s.saving;var result=new CompletableFuture<Void>();s.saving=result;
        PackageFormDrafts.capture(s.view).whenComplete((draft,error)->Minecraft.getInstance().execute(()->{
            try{if(error!=null||!current(s,context,life)||s.epoch!=epoch)throw new IllegalStateException("DELIVERY_DRAFT_STALE_CAPTURE");captured(draft);String value=draft.toString();if(value.equals(s.lastStored)){result.complete(null);return;}
                var envelope=new JsonObject();envelope.add("scope",JSON.toJsonTree(s.scope));envelope.add("draft",draft);String encoded=envelope.toString();
                CompletableFuture.runAsync(()->{try{store().save(s.scope.storageKey(),encoded);}catch(Exception e){throw new CompletionException(e);}},IO).whenComplete((ignored,failed)->Minecraft.getInstance().execute(()->{
                    if(failed!=null){s.status="FAILED";s.diagnostic="DELIVERY_DRAFT_SAVE_FAILED";emit(s,false);result.completeExceptionally(failed);}
                    else{if(!s.closed&&s.epoch==epoch){s.lastStored=value;if(!s.status.equals("RESTORED"))s.status="SAVED";emit(s,false);}result.complete(null);}
                }));
            }catch(Exception failed){result.completeExceptionally(failed);}
        }));
        result.whenComplete((v,e)->Minecraft.getInstance().execute(()->{if(s.saving==result)s.saving=null;if(force&&e!=null&&!s.closed){s.status="FAILED";s.diagnostic="DELIVERY_DRAFT_SAVE_FAILED";emit(s,false);}}));return result;
    }
    public static CompletableFuture<Receipt> action(String view,String action){
        var s=states.get(view);var context=PackageContentClient.session(view);if(s==null||context==null||s.closed)return CompletableFuture.failedFuture(new SecurityException("DELIVERY_DRAFT_CONTEXT"));
        DeliveryProtocol.deliveryId(context.binding());
        if(action.equals("continue")){s.epoch++;s.candidate=null;s.restoring=false;s.loaded=true;s.sessionOnly=true;s.status="SESSION_ONLY";s.diagnostic="";emit(s,true);return CompletableFuture.completedFuture(new Receipt(UUID.randomUUID(),Code.APPLIED,Map.of("localOnly","true","saved","false")));}
        if(!action.equals("restore")||!s.loaded||s.candidate==null||s.restoring)return CompletableFuture.failedFuture(new IllegalStateException("DELIVERY_DRAFT_RESTORE_STATE"));
        s.restoring=true;s.status="RESTORING";long epoch=++s.epoch,life=PackageContentClient.lifecycle(view);var wanted=s.candidate.deepCopy();emit(s,true);var result=new CompletableFuture<Receipt>();
        PackageFormDrafts.capture(view).thenCompose(initial->{captured(initial);if(!current(s,context,life)||s.epoch!=epoch)throw new IllegalStateException("DELIVERY_DRAFT_STALE_RESTORE");return PackageFormDrafts.restoreDelivery(view,wanted,initial);}).thenCompose(restored->{if(!restored.get("status").getAsString().equals("DRAFT_RESTORED"))throw new IllegalStateException("DELIVERY_DRAFT_TARGET_CHANGED");return PackageFormDrafts.capture(view);}).whenComplete((actual,error)->Minecraft.getInstance().execute(()->{
            if(s.closed||states.get(view)!=s||s.epoch!=epoch){result.completeExceptionally(new IllegalStateException("DELIVERY_DRAFT_RESTORE_CANCELLED"));return;}s.restoring=false;
            if(error!=null||!current(s,context,life)||!FormDraftParity.matches(wanted,actual)){s.status="FAILED";s.diagnostic="DELIVERY_DRAFT_RESTORE_NOT_VERIFIED";emit(s,true);result.completeExceptionally(new IllegalStateException(s.diagnostic));return;}
            s.candidate=null;s.status="RESTORED";s.diagnostic="";s.lastStored=wanted.toString();emit(s,true);result.complete(new Receipt(UUID.randomUUID(),Code.APPLIED,Map.of("localOnly","true","eventsDispatched","false","businessSubmitted","false")));
        }));return result;
    }
    public static void tick(int ticks){int started=0;for(var s:List.copyOf(states.values())){if(started>=1)break;if(!s.loaded||s.closed||s.restoring||s.candidate!=null||s.sessionOnly||s.status.equals("FAILED")||s.saving!=null||ticks<s.after||PackageContentClient.session(s.view)==null)continue;s.after=ticks+40;started++;save(s,false).exceptionally(e->null);}}
    public static void close(String view){var s=states.remove(view);if(s!=null){s.closed=true;s.epoch++;}}
    public static void clear(){for(String view:List.copyOf(states.keySet()))close(view);}
}
