package dev.mineagent.runtime.client.webui;
import java.util.*;

/** Render-thread queue: allow the current extracted GUI and the next frame to finish before release. */
public final class FrameResourceRetirement {
    private record Pending(long frame,Runnable close){}
    private final Map<Object,Pending> pending=new IdentityHashMap<>();
    private long frame;
    public void retire(Object resource,Runnable close){pending.putIfAbsent(Objects.requireNonNull(resource),new Pending(frame+2,Objects.requireNonNull(close)));}
    public boolean contains(Object resource){return pending.containsKey(resource);}
    public int size(){return pending.size();}
    public void afterFrame(){frame++;release(false);}
    public void drain(){release(true);}
    private void release(boolean all){
        var actions=new ArrayList<Runnable>();
        pending.values().removeIf(p->{if(all||p.frame()<=frame){actions.add(p.close());return true;}return false;});
        RuntimeException failure=null;
        for(var action:actions)try{action.run();}catch(RuntimeException e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        if(failure!=null)throw failure;
    }
}
