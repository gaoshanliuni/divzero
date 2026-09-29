package dev.mineagent.runtime.neoforge.body;
import net.minecraft.server.MinecraftServer;import java.util.*;
/** Server-wide round-robin queue; unfinished searches do not monopolize a tick. */
public final class NativeNavigationBudget {
    private static final Map<MinecraftServer,NativeNavigationBudget> ALL=new WeakHashMap<>();
    private final LinkedHashMap<UUID,Integer> waiting=new LinkedHashMap<>();private int tick=-1,remaining;private long deadline;
    public static NativeNavigationBudget get(MinecraftServer server){return ALL.computeIfAbsent(server,k->new NativeNavigationBudget());}
    public int claim(UUID id,int now){if(now!=tick){tick=now;remaining=1024;deadline=System.nanoTime()+3_000_000;waiting.entrySet().removeIf(e->now-e.getValue()>3);}waiting.put(id,now);if(remaining<=0||!waiting.keySet().iterator().next().equals(id)||System.nanoTime()>=deadline)return 0;waiting.remove(id);int result=Math.min(96,remaining);remaining-=result;return result;}
    public boolean timeAvailable(){return System.nanoTime()<deadline;}
}
