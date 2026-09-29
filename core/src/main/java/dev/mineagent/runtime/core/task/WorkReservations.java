package dev.mineagent.runtime.core.task;
import java.util.*;
/** Owner is the skill session, not the AI UUID; stale releases cannot unlock another worker. */
public final class WorkReservations<K> {
    private record Hold(UUID owner,long expires){}private final Map<K,Hold> holds=new HashMap<>();
    public boolean reserve(K key,UUID owner,long tick,long ttl){var old=holds.get(key);if(old!=null&&old.expires>tick&&!old.owner.equals(owner))return false;holds.put(key,new Hold(owner,tick+ttl));return true;}
    public void release(K key,UUID owner){var old=holds.get(key);if(old!=null&&old.owner.equals(owner))holds.remove(key);}
    public void release(UUID owner){holds.entrySet().removeIf(e->e.getValue().owner.equals(owner));}
    public void expire(long tick){holds.entrySet().removeIf(e->e.getValue().expires<=tick);}
}
