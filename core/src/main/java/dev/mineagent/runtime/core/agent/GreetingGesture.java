package dev.mineagent.runtime.core.agent;

import java.util.ArrayDeque;

/** Repeated press/release edges, not a held Shift key or repeated network samples. */
public final class GreetingGesture {
    private final ArrayDeque<Long> presses=new ArrayDeque<>();private boolean down;private long lastTick=Long.MIN_VALUE,until=-1,nextGesture;
    public boolean observe(long tick,boolean crouching){
        if(tick==lastTick)return tick<=until;lastTick=tick;
        while(!presses.isEmpty()&&tick-presses.peekFirst()>60)presses.removeFirst();
        if(crouching&&!down&&tick>=nextGesture){presses.addLast(tick);if(presses.size()>=3){until=tick+60;nextGesture=tick+80;presses.clear();}}
        down=crouching;return tick<=until;
    }
    public boolean active(long tick){return tick<=until;}
    public static boolean replyCrouch(long elapsed){return elapsed>=0&&elapsed<48&&elapsed%16<8;}
}
