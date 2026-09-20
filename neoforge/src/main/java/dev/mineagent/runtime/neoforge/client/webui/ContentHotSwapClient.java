package dev.mineagent.runtime.neoforge.client.webui;
import com.google.gson.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.client.webui.UiPackageTransition;
import net.minecraft.client.Minecraft;
import java.util.*;
import java.util.concurrent.*;

/** Explicit user apply: durable source snapshots, readonly candidate checks, commit, new readonly realms and window swap. */
public final class ContentHotSwapClient {
    private static Active active;
    public record Outcome(UUID operation,String state,boolean commitAcknowledged){}
    private static final Map<UUID,Outcome> outcomes=new LinkedHashMap<>();
    public static Outcome outcome(UUID operation){return outcomes.get(operation);}
    private record Swap(UUID id,UiPackageTransition.Snapshot source,Session target,CompletableFuture<Void> future){}
    private static final class Active {
        final UUID operation;final Session shell;final Set<String> sources=new HashSet<>(),opened=new HashSet<>(),humanEdited=new HashSet<>(),expectedClose=new HashSet<>();final Map<String,Long> loads=new HashMap<>();
        final Map<UUID,Swap> swaps=new HashMap<>();UiPackageTransition flow;CompletableFuture<Receipt> future;
        Active(UUID operation,Session shell){this.operation=operation;this.shell=shell;}
    }
    private ContentHotSwapClient(){}
    public static CompletableFuture<Receipt> apply(UUID operation,UUID pkg,long currentRevision,boolean rollback){
        if(active!=null){if(active.operation.equals(operation)&&active.future!=null)return active.future;return CompletableFuture.failedFuture(new IllegalStateException("UI_TRANSITION_BUSY"));}
        var sessions=PackageContentClient.transitionSources(pkg,currentRevision);String action=rollback?"package.patchRollback":"package.patchApply";
        if(sessions.isEmpty())return UiClientSessions.command(action,Map.of("operationId",operation.toString()),UUID.randomUUID());
        var a=new Active(operation,UiClientSessions.current());sessions.forEach(s->a.sources.add(s.binding().viewId()));active=a;
        var port=new UiPackageTransition.Port(){
            public boolean current(UiPackageTransition.Snapshot snapshot){return active==a&&a.shell==UiClientSessions.current()&&WebGuiHostAdapter.INSTANCE.ready()
                    &&snapshot.source().equals(PackageContentClient.rawSession(snapshot.source().binding().viewId()))
                    &&Objects.equals(a.loads.get(snapshot.source().binding().viewId()),PackageContentClient.lifecycle(snapshot.source().binding().viewId()));}
            public CompletableFuture<UiPackageTransition.Snapshot> capture(Session source){
                progress(a,"CAPTURING");String view=source.binding().viewId();
                return ContentTakeoverClient.capturePersisted(view).thenApply(draft->{a.loads.put(view,PackageContentClient.lifecycle(view));return new UiPackageTransition.Snapshot(source,draft.toString());});
            }
            public CompletableFuture<Session> preview(UiPackageTransition.Snapshot snapshot){
                progress(a,"PREVIEW");return opened(a,PackagePreviewClient.openCandidate(pkg,currentRevision+1,UUID.fromString(snapshot.source().binding().targetObjectId()),operation));
            }
            public CompletableFuture<Void> restore(UiPackageTransition.Snapshot snapshot,Session target){
                progress(a,target.binding().preview()?"CHECKING_CANDIDATE":"RESTORING");
                return ContentTakeoverClient.restorePrepared(snapshot.source(),target,JsonParser.parseString(snapshot.draft()).getAsJsonObject())
                        .thenCompose(v->PackagePageAgent.captureManagedView(target.binding().viewId())).thenApply(image->null);
            }
            public CompletableFuture<Receipt> commit(){progress(a,"COMMITTING");return UiClientSessions.command(action,Map.of("operationId",operation.toString()),UUID.randomUUID());}
            public CompletableFuture<Session> open(UiPackageTransition.Snapshot snapshot){
                return opened(a,PackagePreviewClient.openForRestore(pkg,currentRevision+1,UUID.fromString(snapshot.source().binding().targetObjectId())));
            }
            public CompletableFuture<Void> swap(UiPackageTransition.Snapshot snapshot,Session target){
                if(!target.equals(PackageContentClient.session(target.binding().viewId())))return CompletableFuture.failedFuture(new IllegalStateException("STALE_RESTORED_DOCUMENT"));
                progress(a,"SWAPPING");var future=new CompletableFuture<Void>();UUID id=UUID.randomUUID();var swap=new Swap(id,snapshot,target,future);a.swaps.put(id,swap);a.expectedClose.add(snapshot.source().binding().viewId());
                WebGuiHostAdapter.INSTANCE.emit("contentHotSwap",Map.of("id",id,"oldViewId",snapshot.source().binding().viewId(),"viewId",target.binding().viewId(),"packageId",pkg,"revision",currentRevision+1));
                future.orTimeout(10,TimeUnit.SECONDS).whenComplete((v,e)->Minecraft.getInstance().execute(()->a.swaps.remove(id)));return future;
            }
            public void close(Session target){String view=target.binding().viewId();if(a.humanEdited.contains(view)||ContentTakeoverClient.hasHumanEdits(view))WebGuiHostAdapter.INSTANCE.emit("contentError",Map.of("viewId",view,"code","INTERRUPTED_DRAFT_RETAINED"));else closeView(view);}
        };
        try{
            a.flow=new UiPackageTransition(sessions,currentRevision+1,port,rollback);
            a.future=a.flow.start().whenComplete((receipt,error)->{
                outcomes.put(a.operation,new Outcome(a.operation,a.flow.phase(),a.flow.committed()));while(outcomes.size()>32)outcomes.remove(outcomes.keySet().iterator().next());
                progress(a,error==null?"COMPLETE":a.flow.committed()?"COMMITTED_RESTORE_FAILED":"NOT_COMMITTED");
                if(error!=null){Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();String code=cause.getMessage();dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("UI_PACKAGE_TRANSITION_FAILED operation={} committed={} code={}",a.operation,a.flow.committed(),code!=null&&code.matches("[A-Z_]{1,80}")?code:cause.getClass().getSimpleName());}
                if(active==a)active=null;
            });return a.future;
        }catch(Exception failure){active=null;return CompletableFuture.failedFuture(failure);}
    }
    private static CompletableFuture<Session> opened(Active a,CompletableFuture<Receipt> request){
        return request.thenCompose(receipt->{
            if(receipt.code()!=Code.ACCEPTED)throw new IllegalStateException("UI_TRANSITION_OPEN_FAILED");
            String view=receipt.values().get("viewId");a.opened.add(view);var result=new CompletableFuture<Session>();awaitReady(a,view,result,System.currentTimeMillis()+15_000);return result;
        });
    }
    private static void awaitReady(Active a,String view,CompletableFuture<Session> result,long deadline){
        if(active!=a||a.shell!=UiClientSessions.current()||System.currentTimeMillis()>=deadline){if(!a.humanEdited.contains(view))closeView(view);result.completeExceptionally(new IllegalStateException("TRANSITION_VIEW_NOT_READY"));return;}
        var session=PackageContentClient.session(view);if(session!=null){result.complete(session);return;}
        if(PackageContentClient.rawSession(view)==null){result.completeExceptionally(new IllegalStateException("VIEW_NOT_RENDERED"));return;}
        CompletableFuture.delayedExecutor(100,TimeUnit.MILLISECONDS).execute(()->Minecraft.getInstance().execute(()->awaitReady(a,view,result,deadline)));
    }
    public static void acknowledge(JsonObject data){
        var a=active;if(a==null)throw new IllegalStateException("STALE_TRANSITION");var swap=a.swaps.remove(UUID.fromString(data.get("id").getAsString()));
        if(swap==null)throw new IllegalStateException("STALE_TRANSITION");
        if(!"SWAPPED".equals(data.get("status").getAsString())||!swap.target().equals(PackageContentClient.session(swap.target().binding().viewId()))){swap.future().completeExceptionally(new IllegalStateException("WINDOW_SWAP_REJECTED"));return;}
        swap.future().complete(null);
    }
    public static void cancel(UUID operation){if(active!=null&&active.operation.equals(operation))active.flow.cancel();}
    public static void humanInput(String view){if(active!=null&&(active.sources.contains(view)||active.opened.contains(view))){active.humanEdited.add(view);active.flow.cancel();}}
    public static void closed(String view){if(active!=null&&active.sources.contains(view)&&!active.expectedClose.contains(view))active.flow.cancel();}
    public static void clear(){outcomes.clear();var a=active;if(a==null)return;a.flow.cancel();active=null;for(var swap:List.copyOf(a.swaps.values()))swap.future().completeExceptionally(new IllegalStateException("VIEW_NOT_RENDERED"));a.swaps.clear();}
    private static void closeView(String view){var host=WebGuiHostAdapter.INSTANCE;if(host.ready())host.emit("closeManagedView",Map.of("viewId",view));}
    private static void progress(Active a,String phase){dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.info("UI_PACKAGE_TRANSITION operation={} phase={}",a.operation,phase);WebGuiHostAdapter.INSTANCE.emit("uiPackageTransition",Map.of("operationId",a.operation,"phase",phase));}
}
