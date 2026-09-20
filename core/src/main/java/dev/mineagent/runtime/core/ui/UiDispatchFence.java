package dev.mineagent.runtime.core.ui;
import dev.mineagent.runtime.api.task.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
/** Server-observed authority exported as a one-way, thread-safe permit to the actual Worker request lane. */
public final class UiDispatchFence {
    private final ManagedTask task;private final Session session;private final Clock clock;
    private final AtomicBoolean valid=new AtomicBoolean(true);private volatile boolean rendered;
    public UiDispatchFence(ManagedTask task,Session session,Clock clock){
        this.task=Objects.requireNonNull(task);this.session=Objects.requireNonNull(session);this.clock=Objects.requireNonNull(clock);
        var b=session.binding();if(b.actorKind()!=ActorKind.AGENT||!task.taskId().equals(b.taskId())||!task.agentId().equals(b.actorId())||!task.ownerPlayerId().equals(b.viewerPlayerId())||!task.worldId().equals(b.worldId())||task.intentRevision()!=b.taskRevision())throw new IllegalArgumentException("UI_DISPATCH_CONTEXT");observeTask(task);
    }
    public boolean observeTask(ManagedTask live){
        if(live==null||live.status()!=TaskStatus.RUNNING||!task.taskId().equals(live.taskId())||!task.worldId().equals(live.worldId())||!task.agentId().equals(live.agentId())||!task.ownerPlayerId().equals(live.ownerPlayerId())||task.intentRevision()!=live.intentRevision())revoke();return valid.get();
    }
    public boolean observeSession(Session current){
        if(current==null||current.status()!=Status.RENDERED||!session.sessionId().equals(current.sessionId())||!session.serverInstanceId().equals(current.serverInstanceId())||!session.binding().equals(current.binding())||session.pageGeneration()!=current.pageGeneration()||session.controlEpoch()!=current.controlEpoch()||current.expiresAtMillis()!=session.expiresAtMillis())revoke();
        rendered=true;return allowed();
    }
    public boolean allowed(){if(clock.millis()>=session.expiresAtMillis())revoke();return valid.get()&&rendered;}
    public void revoke(){valid.set(false);}
}
