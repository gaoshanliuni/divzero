package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.client.webui.ContentDraftScope;
import dev.mineagent.runtime.client.webui.UiStateStore;
import net.minecraft.client.Minecraft;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Capture locally, restore into a new read-only document, then explicitly activate a new PLAYER session. */
public final class ContentTakeoverClient {
    private static final Gson JSON=new Gson();
    private static final ExecutorService IO=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"mineagent-content-drafts");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final Map<String,Restore> restores=new HashMap<>();
    private static final class Restore {
        final String oldView;final ContentDraftScope scope;final JsonObject draft;boolean restoring,restored,activated,activating,userEdited;
        UUID restoredSession;long restoredPage,restoredEpoch;
        long generation;
        Session transitionSource;boolean preview,humanEdited;
        final CompletableFuture<Void> completion=new CompletableFuture<>();
        Restore(String oldView,ContentDraftScope scope,JsonObject draft){this.oldView=oldView;this.scope=scope;this.draft=draft;}
    }
    private ContentTakeoverClient(){}
    public static CompletableFuture<Receipt> begin(String oldView){
        var source=PackageContentClient.rawSession(oldView);if(source==null)return CompletableFuture.failedFuture(new IllegalStateException("VIEW_NOT_RENDERED"));
        var scope=ContentDraftScope.of(serverId(),source.binding());
        return capturePersisted(oldView).thenCompose(draft->open(scope,oldView,draft));
    }
    public static CompletableFuture<JsonObject> capturePersisted(String oldView){
        var source=PackageContentClient.rawSession(oldView);if(source==null)return CompletableFuture.failedFuture(new IllegalStateException("VIEW_NOT_RENDERED"));
        var scope=ContentDraftScope.of(serverId(),source.binding());var store=store();
        PackageContentClient.freezeForTakeover(oldView);
        long load=PackageContentClient.lifecycle(oldView);
        return PackageFormDrafts.captureAndSeal(oldView).thenCompose(draft->{
            requireCaptured(draft);
            return CompletableFuture.runAsync(()->{try{var envelope=new JsonObject();envelope.add("scope",JSON.toJsonTree(scope));envelope.add("draft",draft);store.save(scope.storageKey(),envelope.toString());}catch(Exception e){throw new CompletionException(e);}},IO)
                    .thenCompose(ignored->onClient(()->{if(!source.equals(PackageContentClient.rawSession(oldView))||load!=PackageContentClient.lifecycle(oldView))throw new IllegalStateException("STALE_DRAFT_SOURCE");return CompletableFuture.completedFuture(draft);}));
        });
    }
    public static CompletableFuture<Void> restorePrepared(Session source,Session target,JsonObject draft){
        dev.mineagent.runtime.client.webui.UiPackageTransition.requireTarget(source,target,target.binding().packageRevision(),target.binding().preview());requireCaptured(draft);
        var state=new Restore(source.binding().viewId(),ContentDraftScope.of(serverId(),target.binding()),draft);state.transitionSource=source;state.preview=target.binding().preview();
        register(target.binding().viewId(),state);return state.completion;
    }
    public static CompletableFuture<Receipt> resume(UUID pkg,long revision,UUID target){
        return PackagePreviewClient.openForRestore(pkg,revision,target).thenCompose(receipt->{
            if(receipt.code()!=Code.ACCEPTED)return CompletableFuture.completedFuture(receipt);
            String view=receipt.values().get("viewId");var session=PackageContentClient.rawSession(view);String server=serverId();
            var scope=ContentDraftScope.of(server,session.binding());var store=store();
            return CompletableFuture.supplyAsync(()->{try{return store.load(scope.storageKey());}catch(Exception e){throw new CompletionException(e);}},IO)
                    .thenCompose(saved->onClient(()->{
                        var envelope=JsonParser.parseString(saved).getAsJsonObject();
                        if(!scope.equals(JSON.fromJson(envelope.get("scope"),ContentDraftScope.class)))throw new SecurityException("DRAFT_SCOPE_MISMATCH");
                        var draft=envelope.getAsJsonObject("draft");requireCaptured(draft);register(view,new Restore("",scope,draft));return CompletableFuture.completedFuture(receipt);
                    }));
        });
    }
    private static CompletableFuture<Receipt> open(ContentDraftScope scope,String oldView,JsonObject draft){
        return PackagePreviewClient.openForRestore(scope.packageId(),scope.packageRevision(),UUID.fromString(scope.target())).thenApply(receipt->{
            if(receipt.code()==Code.ACCEPTED)register(receipt.values().get("viewId"),new Restore(oldView,scope,draft));return receipt;
        });
    }
    private static void register(String view,Restore state){
        if(restores.size()>=32)throw new IllegalStateException("DRAFT_RESTORE_BUDGET");restores.put(view,state);ready(view);
    }
    public static void ready(String view){
        var state=restores.get(view);var session=PackageContentClient.session(view);
        if(state!=null&&state.activating&&session!=null&&session.binding().capabilities().contains("scoreview.patch")){
            state.activating=false;state.activated=true;WebGuiHostAdapter.INSTANCE.emit("takeoverActivated",Map.of("viewId",view,"oldViewId",state.oldView));return;
        }
        if(state==null||state.restoring||state.restored||state.activated||session==null)return;
        if(state.transitionSource!=null){
            dev.mineagent.runtime.client.webui.UiPackageTransition.requireTarget(state.transitionSource,session,state.scope.packageRevision(),state.preview);
            if(!state.scope.equals(ContentDraftScope.of(serverId(),session.binding())))throw new SecurityException("DRAFT_SCOPE_MISMATCH");
        }else state.scope.requireRestore(serverId(),session.binding(),state.oldView);
        state.restoring=true;attempt(view,state,0);
    }
    private static void attempt(String view,Restore state,int attempt){
        if(restores.get(view)!=state)return;
        long generation=state.generation;
        if(state.userEdited){failed(view,"USER_EDIT_DURING_RESTORE");return;}
        PackageFormDrafts.restore(view,state.draft).whenComplete((result,error)->Minecraft.getInstance().execute(()->{
            if(restores.get(view)!=state||state.generation!=generation)return;
            if(error!=null){failed(view,"DRAFT_RESTORE_FAILED");return;}
            if(!result.get("status").getAsString().equals("DRAFT_RESTORED")){
                if(attempt<10&&!state.userEdited){later(()->{if(state.generation==generation&&restores.get(view)==state)attempt(view,state,attempt+1);});return;}failed(view,result.get("status").getAsString());return;
            }
            later(()->PackageFormDrafts.capture(view).whenComplete((actual,failure)->Minecraft.getInstance().execute(()->{
                if(restores.get(view)!=state||state.generation!=generation)return;
                if(failure==null&&FormDraftParity.matches(state.draft,actual)&&!state.userEdited){
                    var current=PackageContentClient.session(view);if(current==null){failed(view,"VIEW_NOT_RENDERED");return;}
                    CompletableFuture<Void> saved=state.preview?CompletableFuture.completedFuture(null):persist(state.scope,actual);
                    saved.whenComplete((ignored,saveError)->Minecraft.getInstance().execute(()->{
                        if(restores.get(view)!=state||state.generation!=generation)return;
                        if(saveError!=null||state.userEdited||!current.equals(PackageContentClient.session(view))){failed(view,"RESTORED_DRAFT_NOT_DURABLE_OR_STALE");return;}
                        state.restored=true;state.restoring=false;if(!state.preview)WebGuiHostAdapter.INSTANCE.emit("takeoverReady",Map.of("viewId",view,"oldViewId",state.oldView,"controlCount",state.draft.getAsJsonArray("controls").size()));
                        state.restoredSession=current.sessionId();state.restoredPage=current.pageGeneration();state.restoredEpoch=current.controlEpoch();state.completion.complete(null);
                    }));
                }else if(attempt<10&&!state.userEdited)attempt(view,state,attempt+1);else failed(view,"DRAFT_NOT_STABLE");
            })));
        }));
    }
    public static CompletableFuture<Receipt> activate(String view,UUID operation){
        var state=restores.get(view);var source=PackageContentClient.session(view);
        if(state==null||!state.restored||state.activated||source==null)return CompletableFuture.failedFuture(new IllegalStateException("DRAFT_NOT_READY"));
        if(!source.sessionId().equals(state.restoredSession)||source.pageGeneration()!=state.restoredPage||source.controlEpoch()!=state.restoredEpoch)return CompletableFuture.failedFuture(new IllegalStateException("STALE_DRAFT_DOCUMENT"));
        return UiClientSessions.command("ui.takeoverActivate",Map.of("restoreSessionId",source.sessionId().toString(),"confirmed","true"),operation).thenApply(receipt->{
            if(receipt.code()!=Code.APPLIED)return receipt;
            var target=JSON.fromJson(receipt.values().get("session"),Session.class);
            state.activating=true;PackageContentClient.activateTakeover(source,target);return receipt;
        });
    }
    public static void humanInput(String view){var state=restores.get(view);if(state!=null){state.humanEdited=true;if(!state.restored)state.userEdited=true;}}
    public static boolean hasHumanEdits(String view){var state=restores.get(view);return state!=null&&state.humanEdited;}
    public static void reloading(String view){var state=restores.get(view);if(state!=null&&!state.activated&&!state.activating){state.generation++;state.restored=false;state.restoring=false;state.userEdited=false;WebGuiHostAdapter.INSTANCE.emit("takeoverPending",Map.of("viewId",view));}}
    public static void close(String view){var state=restores.remove(view);if(state!=null)state.completion.completeExceptionally(new IllegalStateException("VIEW_NOT_RENDERED"));}
    public static void clear(){for(var view:List.copyOf(restores.keySet()))close(view);PackageFormDrafts.clear();}
    private static void requireCaptured(JsonObject draft){if(draft==null||!draft.has("status")||!draft.get("status").getAsString().equals("DRAFT_CAPTURED")||draft.toString().length()>49152)throw new IllegalStateException("DRAFT_CAPTURE_FAILED");}
    private static void failed(String view,String code){var s=restores.get(view);if(s!=null){s.restoring=false;s.completion.completeExceptionally(new IllegalStateException(code));}WebGuiHostAdapter.INSTANCE.emit("contentError",Map.of("viewId",view,"code",code));}
    private static void later(Runnable work){CompletableFuture.delayedExecutor(200,TimeUnit.MILLISECONDS).execute(()->Minecraft.getInstance().execute(work));}
    private static String serverId(){var s=Minecraft.getInstance().getCurrentServer();return s==null?"local-integrated":s.ip;}
    private static UiStateStore store(){return new UiStateStore(Minecraft.getInstance().gameDirectory.toPath().resolve("mineagent-runtime-data/content-drafts"));}
    private static CompletableFuture<Void> persist(ContentDraftScope scope,JsonObject draft){
        var store=store();String encoded;var envelope=new JsonObject();envelope.add("scope",JSON.toJsonTree(scope));envelope.add("draft",draft);encoded=envelope.toString();
        return CompletableFuture.runAsync(()->{try{store.save(scope.storageKey(),encoded);}catch(Exception error){throw new CompletionException(error);}},IO);
    }
    private static <T> CompletableFuture<T> onClient(Supplier<CompletableFuture<T>> work){var result=new CompletableFuture<T>();Minecraft.getInstance().execute(()->{try{work.get().whenComplete((v,e)->{if(e!=null)result.completeExceptionally(e);else result.complete(v);});}catch(Exception e){result.completeExceptionally(e);}});return result;}
}
