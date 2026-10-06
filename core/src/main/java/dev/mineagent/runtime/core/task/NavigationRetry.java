package dev.mineagent.runtime.core.task;

/** Retry timing belongs to the intent, not to the presence of a route. */
public final class NavigationRetry {
    private long next;private int failures;
    public boolean ready(long tick){return tick>=next;}
    public void reset(long tick){next=tick;failures=0;}
    public void failed(long tick){next=tick+Math.min(160,20L<<Math.min(failures++,3));}
    public void failed(long tick,int initial,int maximum){if(initial<1||maximum<initial)throw new IllegalArgumentException("RETRY_INTERVAL");next=tick+Math.min(maximum,(long)initial<<Math.min(failures++,3));}
    public void waitUntil(long tick){next=Math.max(next,tick);}
    public void succeeded(long tick){failures=0;next=tick+20;}
    public int failures(){return failures;}
    public long next(){return next;}
}
