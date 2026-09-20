package dev.mineagent.runtime.client.webui;
import java.util.*;
import java.util.function.BooleanSupplier;
/** Owns deferred paint snapshots until the next extraction boundary. Never changes an extracted frame's image. */
public final class FramePaintQueue {
    private record Paint(Object owner,long bytes,BooleanSupplier current,Runnable apply,Runnable release){}
    private final int maximum;private final long byteBudget;private final ArrayDeque<Paint> pending=new ArrayDeque<>();private long bytes;private int depth;
    public FramePaintQueue(int maximum,long byteBudget){if(maximum<1||byteBudget<1)throw new IllegalArgumentException("PAINT_QUEUE_BUDGET");this.maximum=maximum;this.byteBudget=byteBudget;}
    public boolean inFrame(){return depth>0;}public int pendingCount(){return pending.size();}public long pendingBytes(){return bytes;}
    public void beginFrame(){if(depth>0){depth++;return;}try{drain();}finally{depth=1;}}
    public void endFrame(){if(depth<1)throw new IllegalStateException("PAINT_FRAME_UNBALANCED");depth--;}
    public void submit(Object owner,long size,BooleanSupplier current,Runnable apply,Runnable release){
        Objects.requireNonNull(owner);Objects.requireNonNull(current);Objects.requireNonNull(apply);Objects.requireNonNull(release);
        if(size<1||size>byteBudget||pending.size()>=maximum||bytes>byteBudget-size){release.run();throw new IllegalStateException("PAINT_QUEUE_BUDGET");}
        var item=new Paint(owner,size,current,apply,release);if(!inFrame()){run(item);return;}pending.addLast(item);bytes+=size;
    }
    public void discard(Object owner){var removed=new ArrayList<Paint>();pending.removeIf(p->{if(p.owner()==owner){removed.add(p);bytes-=p.bytes();return true;}return false;});release(removed);}
    public void clear(){var removed=new ArrayList<>(pending);pending.clear();bytes=0;release(removed);}
    private void drain(){var copy=new ArrayList<>(pending);pending.clear();bytes=0;RuntimeException failure=null;for(var p:copy)try{run(p);}catch(RuntimeException e){if(failure==null)failure=e;else failure.addSuppressed(e);}if(failure!=null)throw failure;}
    private static void run(Paint p){try{if(p.current().getAsBoolean())p.apply().run();}finally{p.release().run();}}
    private static void release(List<Paint> paints){RuntimeException failure=null;for(var p:paints)try{p.release().run();}catch(RuntimeException e){if(failure==null)failure=e;else failure.addSuppressed(e);}if(failure!=null)throw failure;}
}
