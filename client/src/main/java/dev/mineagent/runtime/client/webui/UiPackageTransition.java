package dev.mineagent.runtime.client.webui;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import java.util.*;
import java.util.concurrent.*;

/** UI-thread transaction coordinator. Never restores authority, replays a save, or silently rolls back a commit. */
public final class UiPackageTransition {
    public record Snapshot(Session source,String draft){public Snapshot{Objects.requireNonNull(source);Objects.requireNonNull(draft);if(draft.length()>49152)throw new IllegalArgumentException("DRAFT_BUDGET");}}
    public interface Port {
        boolean current(Snapshot snapshot);
        CompletableFuture<Snapshot> capture(Session source);
        CompletableFuture<Session> preview(Snapshot snapshot);
        CompletableFuture<Void> restore(Snapshot snapshot,Session target);
        CompletableFuture<Receipt> commit();
        CompletableFuture<Session> open(Snapshot snapshot);
        CompletableFuture<Void> swap(Snapshot snapshot,Session target);
        void close(Session session);
    }
    private final List<Session> sources;private final long nextRevision;private final Port port;private final boolean rollback;
    private final List<Snapshot> snapshots=new ArrayList<>();private final Set<Session> temporary=new LinkedHashSet<>();
    private String phase="NEW";private boolean cancelled,committed;
    public UiPackageTransition(List<Session> sources,long nextRevision,Port port){this(sources,nextRevision,port,false);}
    public UiPackageTransition(List<Session> sources,long nextRevision,Port port,boolean rollback){
        this.sources=List.copyOf(sources);this.nextRevision=nextRevision;this.port=Objects.requireNonNull(port);this.rollback=rollback;
        if(sources.isEmpty()||sources.size()>12||nextRevision<2||sources.stream().map(s->s.binding().viewId()).distinct().count()!=sources.size())throw new IllegalArgumentException("UI_TRANSITION_SOURCES");
        var first=sources.getFirst();
        for(var s:sources)if(s.binding().preview()||s.binding().packageRevision()!=nextRevision-1||!s.binding().ownerPackageId().equals(first.binding().ownerPackageId())||!s.binding().worldId().equals(first.binding().worldId())||!s.binding().viewerPlayerId().equals(first.binding().viewerPlayerId())||!s.serverInstanceId().equals(first.serverInstanceId()))throw new IllegalArgumentException("UI_TRANSITION_CONTEXT");
    }
    public CompletableFuture<Receipt> start(){
        if(!phase.equals("NEW"))throw new IllegalStateException("UI_TRANSITION_ALREADY_STARTED");phase="CAPTURING";
        CompletableFuture<Void> chain=CompletableFuture.completedFuture(null);
        for(var source:sources)chain=chain.thenCompose(v->{check();return port.capture(source).thenAccept(snapshot->{if(!snapshot.source().equals(source))throw new SecurityException("UI_TRANSITION_SNAPSHOT");snapshots.add(snapshot);check();});});
        if(!rollback)for(int i=0;i<sources.size();i++){final int index=i;chain=chain.thenCompose(v->{check();phase="PREVIEW";var snapshot=snapshots.get(index);
            return port.preview(snapshot).thenCompose(candidate->{temporary.add(candidate);check();requireTarget(snapshot.source(),candidate,nextRevision,true);return port.restore(snapshot,candidate).thenRun(()->{check();port.close(candidate);temporary.remove(candidate);});});});}
        var result=chain.thenCompose(v->{check();phase="COMMITTING";return port.commit();}).thenCompose(receipt->{
            if(receipt.code()!=Code.APPLIED||!(rollback?"ROLLED_BACK":"APPLIED").equals(receipt.values().get("state"))||!Long.toString(nextRevision).equals(receipt.values().get("headRevision")))throw new IllegalStateException("UI_TRANSITION_COMMIT_REJECTED");
            committed=true;check();CompletableFuture<Void> restore=CompletableFuture.completedFuture(null);
            for(var snapshot:List.copyOf(snapshots))restore=restore.thenCompose(v->{check();phase="RESTORING";return port.open(snapshot).thenCompose(target->{
                temporary.add(target);check();requireTarget(snapshot.source(),target,nextRevision,false);
                return port.restore(snapshot,target).thenCompose(ignored->{check();phase="SWAPPING";
                    // After dispatch the host may have installed this realm even if its ack is lost.
                    temporary.remove(target);return port.swap(snapshot,target);
                }).thenRun(()->snapshots.remove(snapshot));
            });});
            return restore.thenApply(v->{phase="COMPLETE";return receipt;});
        });
        return result.whenComplete((v,error)->{if(error!=null){phase=cancelled?"CANCELLED":"FAILED";for(var s:List.copyOf(temporary))port.close(s);temporary.clear();}});
    }
    public void cancel(){cancelled=true;}
    public boolean committed(){return committed;}
    public String phase(){return phase;}
    private void check(){if(cancelled)throw new IllegalStateException("USER_INTERRUPTED");for(var s:snapshots)if(!port.current(s))throw new IllegalStateException("STALE_TRANSITION_SOURCE");}
    public static void requireTarget(Session source,Session next,long revision,boolean preview){
        var a=source.binding();var b=next.binding();
        if(next.status()!=Status.RENDERED||revision!=a.packageRevision()+1||source.sessionId().equals(next.sessionId())||!source.serverInstanceId().equals(next.serverInstanceId())||a.viewId().equals(b.viewId())
                ||!a.ownerPackageId().equals(b.ownerPackageId())||!a.worldId().equals(b.worldId())||!a.viewerPlayerId().equals(b.viewerPlayerId())
                ||!a.packageVersion().equals(b.packageVersion())||!a.entryPath().equals(b.entryPath())||!a.targetObjectId().equals(b.targetObjectId())
                ||b.packageRevision()!=revision||b.preview()!=preview||b.actorKind()!=ActorKind.PLAYER||!b.actorId().equals(b.viewerPlayerId())
                ||b.taskId()!=null||b.taskRevision()!=0||!b.capabilities().equals(Set.of("scoreview.read")))throw new SecurityException("UI_TRANSITION_TARGET");
    }
}
