package dev.mineagent.runtime.core.events;

import dev.mineagent.runtime.api.directory.ObjectRef;
import dev.mineagent.runtime.core.directory.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fixed-area, sampled presence transitions. No chunk loads, synthetic login transitions, or model calls. */
public final class PlayerRegionEvents {
    public static final Set<String> SOURCES=Set.of("PLAYER_REGION_ENTER","PLAYER_REGION_LEAVE");
    public static boolean requested(RuntimeEventStore.Request request){return !Collections.disjoint(request.sources(),SOURCES);}
    public static boolean validQuery(ObjectQuery q){return q!=null&&q.kind()==ObjectRef.Kind.PLAYER&&q.cursor().isEmpty()
            &&(q.region()!=null||q.near()!=null&&q.near().reference().equals("POSITION"));}
    public static boolean eligible(ObjectQuery q,ObjectDirectory.Entry e){
        if(e.ref().kind()!=ObjectRef.Kind.PLAYER||!e.online()||e.position()==null||!q.ids().isEmpty()&&!q.ids().contains(e.ref().id())||!q.team().isEmpty()&&!q.team().equals(e.team()))return false;
        String name=e.name().toLowerCase(Locale.ROOT),filter=q.name().toLowerCase(Locale.ROOT);
        return filter.isEmpty()||(q.match().equals("EXACT")?name.equals(filter):name.startsWith(filter));
    }
    public static boolean inside(ObjectQuery q,ObjectDirectory.Entry e){return ObjectDirectory.matches(q,e,q.near()==null?null:q.near().position());}
    private static boolean continuous(ObjectDirectory.Entry before,ObjectDirectory.Entry after){
        return before.ref().worldId().equals(after.ref().worldId())&&before.ref().id().equals(after.ref().id())&&before.ref().generation().equals(after.ref().generation())
                &&before.name().equals(after.name())&&before.team().equals(after.team());
    }
    public record Change(UUID subscriptionId,long subscriptionRevision,ObjectQuery query,ObjectDirectory.Entry before,long observedBefore,long observedAfter){
        public Change{Objects.requireNonNull(subscriptionId);Objects.requireNonNull(before);
            if(subscriptionRevision<1||!validQuery(query)||!eligible(query,before)||observedBefore<0||observedAfter<observedBefore)throw new IllegalArgumentException("REGION_EVENT_CHANGE");}
    }
    public static void validate(Change change,ObjectDirectory.Entry after,String source){
        if(change==null||after==null||!SOURCES.contains(source)||!eligible(change.query(),after)||!continuous(change.before(),after))throw new IllegalArgumentException("REGION_EVENT_IDENTITY");
        boolean was=inside(change.query(),change.before()),is=inside(change.query(),after);
        if(was==is||!source.equals(is?"PLAYER_REGION_ENTER":"PLAYER_REGION_LEAVE"))throw new IllegalArgumentException("REGION_EVENT_TRANSITION");
    }
    /** Never re-apply a spatial query to only the exit endpoint: that would drop every valid leave event. */
    public static boolean matches(RuntimeEventStore.Subscription sub,RuntimeEventStore.Event event){
        var change=event.region();return change!=null&&change.subscriptionId().equals(sub.id())&&change.subscriptionRevision()==sub.revision()
                &&requested(sub.request())&&sub.request().query().equals(change.query())&&sub.request().sources().contains(event.source());
    }
    @FunctionalInterface public interface Sink{void ingest(RuntimeEventStore.Event event)throws Exception;}
    public record Status(String phase,int trackedPlayers,int pendingEvents,long observedAt,long committedEvents,int consecutiveFailures,String error){}
    private static final class Slot {
        final long revision;final String authority;Map<String,ObjectDirectory.Entry> baseline,next;long observedAt,nextAt,committed;
        final ArrayDeque<RuntimeEventStore.Event> pending=new ArrayDeque<>();int failures;String error="";
        Slot(long revision,String authority,Map<String,ObjectDirectory.Entry> baseline,long at){this.revision=revision;this.authority=authority;this.baseline=baseline;this.observedAt=at;}
    }
    private final Map<UUID,Slot> slots=new HashMap<>();
    /** Subscription definitions are durable; baselines intentionally are not replayed across server epochs. */
    public boolean needsObservation(RuntimeEventStore.Subscription sub){var slot=slots.get(sub.id());return slot==null||slot.revision!=sub.revision()||slot.pending.isEmpty();}
    public void observe(RuntimeEventStore.Subscription sub,String authority,UUID sourceEpoch,List<ObjectDirectory.Entry> observed,long at){
        Objects.requireNonNull(sourceEpoch);if(!sub.state().equals("ACTIVE")||!requested(sub.request())||authority==null||observed.size()>256||at<0)throw new IllegalArgumentException("REGION_SAMPLE_ARGUMENTS");
        var current=new TreeMap<String,ObjectDirectory.Entry>();
        for(var e:observed){if(!e.ref().worldId().equals(sub.creator().scope().worldId())||!eligible(sub.request().query(),e)||current.put(e.ref().id(),e)!=null)throw new IllegalArgumentException("REGION_SAMPLE_IDENTITY");}
        var slot=slots.get(sub.id());
        if(slot==null||slot.revision!=sub.revision()){
            if(slot==null&&slots.size()>=128)throw new IllegalStateException("REGION_SUBSCRIPTION_BUDGET");slots.put(sub.id(),new Slot(sub.revision(),authority,current,at));return;
        }
        if(!slot.authority.equals(authority))throw new SecurityException("REGION_AUTHORITY_CHANGED");
        if(!slot.pending.isEmpty())throw new IllegalStateException("REGION_PENDING_DRAIN_REQUIRED");
        // A wall-clock rollback creates a new baseline, not a negative-duration crossing.
        if(at<slot.observedAt){slot.baseline=current;slot.observedAt=at;slot.error="REGION_CLOCK_REBASED";return;}
        UUID batch=UUID.randomUUID();var events=new ArrayList<RuntimeEventStore.Event>();
        for(var entry:current.entrySet()){
            var before=slot.baseline.get(entry.getKey());var after=entry.getValue();if(before==null||!continuous(before,after))continue;
            boolean was=inside(sub.request().query(),before),is=inside(sub.request().query(),after);if(was==is)continue;
            String source=is?"PLAYER_REGION_ENTER":"PLAYER_REGION_LEAVE";if(!sub.request().sources().contains(source))continue;
            var change=new Change(sub.id(),sub.revision(),sub.request().query(),before,slot.observedAt,at);
            UUID id=UUID.nameUUIDFromBytes((sourceEpoch+"|player-region|"+sub.id()+"|"+sub.revision()+"|"+batch+"|"+entry.getKey()).getBytes(StandardCharsets.UTF_8));
            events.add(new RuntimeEventStore.Event(id,sub.creator().scope().worldId(),sourceEpoch,source,UUID.fromString(entry.getKey()),after,at,null,List.of(),null,null,change));
        }
        slot.error="";slot.failures=0;
        if(events.isEmpty()){slot.baseline=current;slot.observedAt=at;return;}
        slot.next=current;slot.nextAt=at;slot.pending.addAll(events);
    }
    /** An uncertain ingest retains exactly the same event ID; Native bounds retry attempts and can pause the source. */
    public int drain(RuntimeEventStore.Subscription sub,String authority,int limit,Sink sink)throws Exception{
        if(limit<1||limit>16)throw new IllegalArgumentException("REGION_DRAIN_BUDGET");Objects.requireNonNull(sink);var slot=slots.get(sub.id());if(slot==null)return 0;
        if(!sub.state().equals("ACTIVE")||slot.revision!=sub.revision()||!slot.authority.equals(authority))throw new SecurityException("REGION_SOURCE_CHANGED");
        int count=0;
        while(!slot.pending.isEmpty()&&count<limit){var event=slot.pending.getFirst();
            try{sink.ingest(event);}catch(Exception failed){slot.failures++;slot.error="REGION_EVENT_COMMIT_FAILED";throw failed;}
            slot.pending.removeFirst();slot.committed++;slot.failures=0;count++;
        }
        if(slot.pending.isEmpty()&&slot.next!=null){slot.baseline=slot.next;slot.observedAt=slot.nextAt;slot.next=null;slot.error="";}
        return count;
    }
    public Status status(RuntimeEventStore.Subscription sub){var slot=slots.get(sub.id());
        if(!sub.state().equals("ACTIVE"))return new Status("NOT_ACTIVE",0,0,0,0,0,sub.error());
        if(slot==null||slot.revision!=sub.revision())return new Status("AWAITING_BASELINE",0,0,0,0,0,"");
        return new Status(slot.pending.isEmpty()?"MONITORING":slot.failures>0?"BACKPRESSURE":"DRAINING",slot.baseline.size(),slot.pending.size(),slot.observedAt,slot.committed,slot.failures,slot.error);
    }
    public void forget(UUID subscription){slots.remove(subscription);}
    public void retain(Set<UUID> active){slots.keySet().retainAll(active);}
    public void clear(){slots.clear();}
}
