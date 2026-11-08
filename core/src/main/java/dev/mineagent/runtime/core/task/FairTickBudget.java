package dev.mineagent.runtime.core.task;

import java.util.*;

/** FIFO admission across ticks. Charge actual work time, not unrelated work between calls. */
public final class FairTickBudget {
    private final LinkedHashMap<UUID,Long> waiting=new LinkedHashMap<>();
    private long tick=Long.MIN_VALUE,spent,totalGranted,totalDeferred;private int granted;
    public boolean acquire(UUID id,long now,int slices,long nanos){
        if(now!=tick){tick=now;spent=0;granted=0;waiting.entrySet().removeIf(e->now-e.getValue()>40);}
        waiting.put(id,now);
        if(granted>=slices||spent>=nanos||!waiting.keySet().iterator().next().equals(id)){totalDeferred++;return false;}
        waiting.remove(id);granted++;totalGranted++;return true;
    }
    public void charge(long nanos){spent=Math.addExact(spent,Math.max(0,nanos));}
    public Map<String,Object> view(){return Map.of("queued",waiting.size(),"grantedThisTick",granted,"spentNanos",spent,"totalGranted",totalGranted,"totalDeferred",totalDeferred);}
}
