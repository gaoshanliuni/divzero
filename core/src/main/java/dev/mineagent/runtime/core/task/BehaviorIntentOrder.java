package dev.mineagent.runtime.core.task;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Arrival order is retained while ordinary chat waits; only behavior changes fence older work. */
public final class BehaviorIntentOrder<K> {
    private record Arrival<K>(K key, long sequence) {}
    private final Map<K, Long> revisions = new HashMap<>();
    private final Map<UUID, Arrival<K>> arrivals = new HashMap<>();
    private long sequence;

    public synchronized void accept(K key, UUID request, boolean behavior) {
        var previous = arrivals.get(request);
        if (previous != null) {
            if (!previous.key().equals(key)) throw new IllegalArgumentException("BEHAVIOR_REQUEST_REUSED");
            return;
        }
        var arrival = new Arrival<>(key, ++sequence);
        arrivals.put(request, arrival);
        if (behavior) revisions.put(key, arrival.sequence());
    }

    public synchronized boolean current(K key, UUID request) {
        var arrival = arrivals.get(request);
        return arrival != null && arrival.key().equals(key) && arrival.sequence() >= revision(key);
    }

    public synchronized boolean claim(K key, UUID request) {
        if (!current(key, request)) return false;
        revisions.put(key, arrivals.get(request).sequence());
        return true;
    }

    /** A sibling target inherits the original request arrival, never a fresh timestamp. */
    public synchronized boolean currentRelated(K source,K target,UUID request){
        var arrival=arrivals.get(request);return current(source,request)&&arrival.sequence()>=revision(target);
    }
    public synchronized boolean claimRelated(K source,K target,UUID request){
        if(!currentRelated(source,target,request))return false;
        revisions.put(target,arrivals.get(request).sequence());return true;
    }

    public synchronized long revision(K key) { return revisions.getOrDefault(key, 0L); }
    public synchronized void invalidate(K key) { revisions.put(key, ++sequence); }
}
