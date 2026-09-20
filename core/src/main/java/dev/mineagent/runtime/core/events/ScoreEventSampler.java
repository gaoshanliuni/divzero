package dev.mineagent.runtime.core.events;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Per-subscription baselines and immutable pending samples. No score writes or model calls. */
public final class ScoreEventSampler {
    @FunctionalInterface public interface Sink{void ingest(RuntimeEventStore.Event event)throws Exception;}
    public record Status(String phase,int trackedHolders,int pendingEvents,long baselineAt,long incarnation,int failures,String diagnostic){}
    private static final class Slot{
        final long revision;final String security;long incarnation,observedAt,nextAt;Map<String,Integer> before,next;final ArrayDeque<RuntimeEventStore.Event> pending=new ArrayDeque<>();int failures;String diagnostic="";
        Slot(long revision,String security,long incarnation,Map<String,Integer> values,long at){this.revision=revision;this.security=security;this.incarnation=incarnation;before=values;observedAt=at;}
    }
    private final Map<UUID,Slot> slots=new HashMap<>();
    public boolean needsObservation(RuntimeEventStore.Subscription sub){var slot=slots.get(sub.id());return slot==null||slot.revision!=sub.revision()||slot.pending.isEmpty();}
    public void observe(RuntimeEventStore.Subscription sub,String security,String authority,UUID sourceEpoch,long incarnation,Map<String,Integer> values,long at){
        if(!sub.state().equals("ACTIVE")||sub.request().score()==null||security==null||authority==null||sourceEpoch==null||incarnation<0||at<0||values.size()>256)throw new IllegalArgumentException("SCORE_SAMPLE_ARGUMENTS");
        var current=new TreeMap<String,Integer>(values);for(var entry:current.entrySet())if(entry.getValue()==null||!sub.request().score().selects(ScoreEventFilter.name(entry.getKey(),40)))throw new IllegalArgumentException("SCORE_SAMPLE_HOLDER");
        var slot=slots.get(sub.id());if(slot==null||slot.revision!=sub.revision()){if(slot==null&&slots.size()>=128)throw new IllegalStateException("SCORE_SUBSCRIPTION_BUDGET");slots.put(sub.id(),new Slot(sub.revision(),security,incarnation,current,at));return;}
        if(!slot.security.equals(security))throw new SecurityException("SCORE_EVENT_AUTHORITY_CHANGED");
        if(slot.incarnation!=incarnation){if(sub.request().score().replacementPolicy().equals("PAUSE"))throw new IllegalStateException("SCORE_OBJECTIVE_REPLACED");slots.put(sub.id(),new Slot(sub.revision(),security,incarnation,current,at));slots.get(sub.id()).diagnostic="OBJECTIVE_REBASE_NO_INITIAL_EVENT";return;}
        if(!slot.pending.isEmpty())throw new IllegalStateException("SCORE_PENDING_DRAIN_REQUIRED");
        if(at<slot.observedAt){slot.before=current;slot.observedAt=at;slot.diagnostic="SCORE_CLOCK_REBASED";return;}
        var holders=new TreeSet<>(slot.before.keySet());holders.addAll(current.keySet());var batch=UUID.randomUUID();
        var captured=new ArrayList<RuntimeEventStore.Event>();String authorityHash=hash(authority);UUID observer=ScoreEventFilter.systemAuthor(sub.creator().scope().worldId());
        for(String holder:holders){var before=slot.before.get(holder);var after=current.get(holder);if(!sub.request().score().matches(before,after))continue;
            var change=new ScoreEventFilter.Change(sub.id(),sub.revision(),sub.request().score(),incarnation,authorityHash,holder,before,after,slot.observedAt,at);
            UUID id=UUID.nameUUIDFromBytes((sourceEpoch+"|score|"+sub.id()+"|"+sub.revision()+"|"+batch+"|"+holder).getBytes(StandardCharsets.UTF_8));
            captured.add(new RuntimeEventStore.Event(id,sub.creator().scope().worldId(),sourceEpoch,ScoreEventFilter.SOURCE,observer,null,at,null,List.of(),null,null,null,null,change));
        }
        slot.failures=0;slot.diagnostic="";if(captured.isEmpty()){slot.before=current;slot.observedAt=at;}else{slot.pending.addAll(captured);slot.next=current;slot.nextAt=at;}
    }
    public int drain(RuntimeEventStore.Subscription sub,String security,String authority,long incarnation,int maximum,Sink sink)throws Exception{
        if(maximum<1||maximum>16)throw new IllegalArgumentException("SCORE_DRAIN_BUDGET");var slot=slots.get(sub.id());if(slot==null)return 0;
        if(!sub.state().equals("ACTIVE")||slot.revision!=sub.revision()||!slot.security.equals(security))throw new SecurityException("SCORE_EVENT_AUTHORITY_CHANGED");
        if(slot.incarnation!=incarnation){if(sub.request().score().replacementPolicy().equals("PAUSE"))throw new IllegalStateException("SCORE_OBJECTIVE_REPLACED");slot.pending.clear();slot.next=null;slot.diagnostic="OBJECTIVE_REBASE_PENDING";return 0;}
        int count=0;while(!slot.pending.isEmpty()&&count<maximum){var event=slot.pending.getFirst();if(!event.score().authorityHash().equals(hash(authority)))throw new SecurityException("SCORE_EVENT_AUTHORITY_CHANGED");
            try{sink.ingest(event);}catch(Exception failed){slot.failures++;slot.diagnostic="SCORE_EVENT_COMMIT_FAILED";throw failed;}
            slot.pending.removeFirst();slot.failures=0;count++;
        }
        if(slot.pending.isEmpty()&&slot.next!=null){slot.before=slot.next;slot.observedAt=slot.nextAt;slot.next=null;slot.diagnostic="";}return count;
    }
    public Status status(RuntimeEventStore.Subscription sub){var slot=slots.get(sub.id());if(!sub.state().equals("ACTIVE"))return new Status("NOT_ACTIVE",0,0,0,0,0,sub.error());if(slot==null||slot.revision!=sub.revision())return new Status("AWAITING_BASELINE",0,0,0,0,0,"");return new Status(slot.diagnostic.equals("OBJECTIVE_REBASE_PENDING")?"AWAITING_BASELINE":slot.pending.isEmpty()?"MONITORING":slot.failures>0?"BACKPRESSURE":"DRAINING",slot.before.size(),slot.pending.size(),slot.observedAt,slot.incarnation,slot.failures,slot.diagnostic);}
    public void forget(UUID id){slots.remove(id);}public void retain(Set<UUID> active){slots.keySet().retainAll(active);}public void clear(){slots.clear();}
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SCORE_EVENT_HASH",impossible);}}
}
