package dev.mineagent.runtime.core.ui;
import dev.mineagent.runtime.api.ui.UiAgentRpc.*;
import dev.mineagent.runtime.api.ui.UiProtocol.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
/** Correlates real client replies with a current Agent lease; receipts are data, not authority. */
public final class UiAgentRpcBroker {
    public record Flight(Command command,CompletableFuture<String> result){}
    private record Pending(Session session,Flight flight,long deadline){}
    private final Clock clock;private final Function<Session,Code> authority;private final int maximumPending;
    private final Map<UUID,Pending> pending=new LinkedHashMap<>();
    public UiAgentRpcBroker(Clock clock,Function<Session,Code> authority,int maximumPending){
        this.clock=Objects.requireNonNull(clock);this.authority=Objects.requireNonNull(authority);
        if(maximumPending<1||maximumPending>128)throw new IllegalArgumentException("UI_RPC_BUDGET");this.maximumPending=maximumPending;
    }
    public synchronized Flight open(Session session,String kind,String action,long ttl){
        if(ttl<1||ttl>15000)throw new IllegalArgumentException("UI_RPC_TTL");
        if(session.binding().actorKind()!=ActorKind.AGENT||session.status()!=Status.RENDERED)throw new IllegalStateException("VIEW_NOT_RENDERED");
        Code allowed=authority.apply(session);if(allowed!=Code.OK)throw new IllegalStateException(allowed.name());
        var command=new Command(UUID.randomUUID(),session.sessionId(),session.binding().viewId(),session.pageGeneration(),session.controlEpoch(),session.binding().taskRevision(),kind,action);
        tick();if(pending.size()>=maximumPending||pending.values().stream().anyMatch(p->p.session.sessionId().equals(session.sessionId())))throw new IllegalStateException("UI_RPC_BUSY");
        var flight=new Flight(command,new CompletableFuture<>());pending.put(command.requestId(),new Pending(session,flight,clock.millis()+ttl));return flight;
    }
    public synchronized boolean accept(UUID viewer,Reply reply){
        var p=pending.get(reply.requestId());if(p==null)return false;var c=p.flight.command();
        if(!p.session.binding().viewerPlayerId().equals(viewer)||!c.sessionId().equals(reply.sessionId())||!c.viewId().equals(reply.viewId())
                ||c.pageGeneration()!=reply.pageGeneration()||c.controlEpoch()!=reply.controlEpoch()||c.taskRevision()!=reply.taskRevision())return false;
        Code allowed=authority.apply(p.session);
        if(allowed!=Code.OK){fail(reply.requestId(),new IllegalStateException(allowed.name()));return false;}
        if(clock.millis()>=p.deadline){fail(reply.requestId(),new TimeoutException("UI_RPC_TIMEOUT"));return false;}
        pending.remove(reply.requestId());p.flight.result().complete(reply.resultJson());return true;
    }
    public synchronized int pendingCount(){return pending.size();}
    public synchronized void tick(){for(var id:pending.entrySet().stream().filter(e->e.getValue().deadline<=clock.millis()).map(Map.Entry::getKey).toList())fail(id,new TimeoutException("UI_RPC_TIMEOUT"));}
    public synchronized void cancelSession(UUID session,String reason){for(var id:pending.entrySet().stream().filter(e->e.getValue().session.sessionId().equals(session)).map(Map.Entry::getKey).toList())fail(id,new IllegalStateException(reason));}
    public synchronized void clear(){for(var id:List.copyOf(pending.keySet()))fail(id,new IllegalStateException("VIEW_NOT_RENDERED"));}
    private void fail(UUID id,Exception error){var p=pending.remove(id);if(p!=null)p.flight.result().completeExceptionally(error);}
}
