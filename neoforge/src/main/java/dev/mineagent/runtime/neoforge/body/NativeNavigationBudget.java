package dev.mineagent.runtime.neoforge.body;
import net.minecraft.server.MinecraftServer;import java.util.*;
/** Server-wide round-robin queue; unfinished searches do not monopolize a tick. */
public final class NativeNavigationBudget {
    private static final Map<MinecraftServer,NativeNavigationBudget> ALL=new WeakHashMap<>();
    private final MinecraftServer server;
    private dev.mineagent.runtime.core.config.NavigationResourceBudget budget=dev.mineagent.runtime.core.config.NavigationResourceBudget.from(Map.of());
    private final LinkedHashMap<UUID,Integer> waiting=new LinkedHashMap<>();private int tick=-1,remaining,maxReserved,granted,peakWaiting;private long deadline,allocatedNanos,observedTickNanos,configRevision=-1;
    private NativeNavigationBudget(MinecraftServer server){this.server=server;}
    public static NativeNavigationBudget get(MinecraftServer server){return ALL.computeIfAbsent(server,NativeNavigationBudget::new);}
    private void configure(){var config=dev.mineagent.runtime.neoforge.MineAgentRuntimeServices.config(server);if(configRevision!=config.revision()){var snapshot=config.snapshot();budget=dev.mineagent.runtime.core.config.NavigationResourceBudget.from(snapshot.values());configRevision=snapshot.revision();}}
    public int searchNodes(){configure();return budget.searchNodes();}
    public int claim(UUID id,int now){if(now!=tick){configure();tick=now;remaining=budget.tickNodes();observedTickNanos=server.getAverageTickTimeNanos();allocatedNanos=budget.nanosFor(observedTickNanos);deadline=System.nanoTime()+allocatedNanos;waiting.entrySet().removeIf(e->now-e.getValue()>3);}waiting.put(id,now);peakWaiting=Math.max(peakWaiting,waiting.size());if(remaining<=0||!waiting.keySet().iterator().next().equals(id)||System.nanoTime()>=deadline)return 0;waiting.remove(id);int result=Math.min(budget.sliceNodes(),remaining);remaining-=result;maxReserved=Math.max(maxReserved,budget.tickNodes()-remaining);granted++;return result;}
    public boolean timeAvailable(){return System.nanoTime()<deadline;}
    public Map<String,Object> observation(){return Map.of("maxReservedNodesPerTick",maxReserved,"grantedSlices",granted,"peakWaiting",peakWaiting,"queued",waiting.size(),"sliceLimit",budget.sliceNodes(),"tickLimit",budget.tickNodes(),"searchLimit",budget.searchNodes(),"allocatedMillis",allocatedNanos/1_000_000.0,"observedTickMillis",observedTickNanos/1_000_000.0);}
}
