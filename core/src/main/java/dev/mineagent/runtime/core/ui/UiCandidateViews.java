package dev.mineagent.runtime.core.ui;

import dev.mineagent.runtime.api.ui.UiProtocol.*;
import java.time.Clock;
import java.util.*;

/** Server-thread candidate leases. Pending admission exists only during synchronous Session.open. */
public final class UiCandidateViews {
    private record Lease(Binding binding,UUID operation,UUID session,UUID serverInstance,long expires){}
    private final Clock clock;
    private final Map<String,Lease> leases=new HashMap<>();
    public UiCandidateViews(Clock clock){this.clock=Objects.requireNonNull(clock);}

    public void pending(Binding binding,UUID operation,long expires){
        Objects.requireNonNull(binding);Objects.requireNonNull(operation);expire();
        if(!binding.preview()||binding.actorKind()!=ActorKind.PLAYER
                ||!binding.actorId().equals(binding.viewerPlayerId())||binding.taskId()!=null
                ||!binding.capabilities().equals(Set.of("scoreview.read"))||expires<=clock.millis())
            throw new IllegalArgumentException("UI_CANDIDATE_BINDING");
        if(leases.size()>=128||leases.containsKey(binding.viewId()))throw new IllegalStateException("UI_CANDIDATE_BUSY");
        leases.put(binding.viewId(),new Lease(binding,operation,null,null,expires));
    }
    public void admitted(Session session){
        var lease=leases.get(session.binding().viewId());
        if(lease==null||lease.session()!=null||operation(session).isEmpty())throw new IllegalStateException("UI_CANDIDATE_ADMISSION");
        leases.put(session.binding().viewId(),new Lease(lease.binding(),lease.operation(),session.sessionId(),session.serverInstanceId(),
                Math.min(lease.expires(),session.expiresAtMillis())));
    }
    public Optional<UUID> operation(Session session){
        var lease=leases.get(session.binding().viewId());
        if(lease==null||lease.expires()<=clock.millis()||session.expiresAtMillis()<=clock.millis()
                ||session.status()==Status.CLOSED||!lease.binding().equals(session.binding())
                ||lease.session()!=null&&(!lease.session().equals(session.sessionId())||!lease.serverInstance().equals(session.serverInstanceId())))
            return Optional.empty();
        return Optional.of(lease.operation());
    }
    public void remove(String view){leases.remove(view);}
    public void close(UUID session){leases.values().removeIf(l->session.equals(l.session()));}
    public void disconnect(UUID owner){leases.values().removeIf(l->owner.equals(l.binding().viewerPlayerId()));}
    public void expire(){leases.values().removeIf(l->l.expires()<=clock.millis());}
    public int size(){return leases.size();}
    public void clear(){leases.clear();}
}
