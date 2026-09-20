package dev.mineagent.runtime.core.ui;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import java.util.*;
/** One-use activation of a newly opened read-only restoration document, not an upgrade of the old Agent realm. */
public final class UiTakeoverService {
    private final Map<UUID,Binding> pending=new LinkedHashMap<>();
    public synchronized void register(Session session){
        var b=session.binding();
        if(b.preview()||b.actorKind()!=ActorKind.PLAYER||!b.actorId().equals(b.viewerPlayerId())||b.taskId()!=null||!b.capabilities().equals(Set.of("scoreview.read")))throw new IllegalArgumentException("TAKEOVER_READ_ONLY_REQUIRED");
        if(pending.size()>=128)throw new IllegalStateException("TAKEOVER_BUDGET");
        if(pending.putIfAbsent(session.sessionId(),b)!=null)throw new IllegalStateException("TAKEOVER_ALREADY_REGISTERED");
    }
    public synchronized Binding activate(UUID viewer,Session current,boolean confirmed,boolean authorized){
        var b=current.binding();var issued=pending.get(current.sessionId());
        if(!confirmed||!authorized||!b.viewerPlayerId().equals(viewer)||current.status()!=Status.RENDERED||issued==null||!issued.equals(b))throw new SecurityException("TAKEOVER_ACTIVATION_DENIED");
        pending.remove(current.sessionId());
        return new Binding(b.viewId(),b.ownerPackageId(),b.packageRevision(),b.packageVersion(),b.entryPath(),b.worldId(),viewer,viewer,ActorKind.PLAYER,null,0,b.targetObjectId(),false,Set.of("scoreview.read","scoreview.patch"));
    }
    public synchronized void close(UUID session){pending.remove(session);}
    public synchronized void disconnect(UUID viewer){pending.values().removeIf(b->b.viewerPlayerId().equals(viewer));}
    public synchronized void clear(){pending.clear();}
}
