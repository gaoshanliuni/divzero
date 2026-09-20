package dev.mineagent.runtime.client.webui;
import java.util.*;
import java.util.function.*;
/** Main-thread request draining and identity fence. Callbacks may synchronously clear or replace the current scope. */
public final class UiPendingCallbacks {
    private UiPendingCallbacks(){}
    public static boolean current(Object dispatched,Object current,Object peer,Object currentPeer,boolean rendered){return dispatched!=null&&dispatched==current&&peer!=null&&peer==currentPeer&&rendered;}
    public static <K,V> void expire(Map<K,V> requests,Predicate<V> expired,Consumer<V> complete){
        var entries=requests.entrySet().stream().filter(e->expired.test(e.getValue())).map(e->Map.entry(e.getKey(),e.getValue())).toList();
        for(var e:entries)if(requests.remove(e.getKey(),e.getValue()))complete.accept(e.getValue());
    }
}
