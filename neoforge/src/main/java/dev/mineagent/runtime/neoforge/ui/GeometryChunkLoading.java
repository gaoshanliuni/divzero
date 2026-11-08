package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.neoforge.MineAgentRegistries;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** One moving lease per geometry job, shared native tickets, FIFO admission. No blocking getChunk call. */
final class GeometryChunkLoading {
    static final String TOTAL="resources.geometry.chunkTickets",PER_TICK="resources.geometry.chunkLoadsPerTick";
    private record Key(ServerLevel level,int x,int z) {ChunkPos position(){return new ChunkPos(x,z);}}
    private static final class Held {final Set<UUID> owners=new HashSet<>();CompletableFuture<?> loading;}
    private final MinecraftServer server;
    private final Map<Key,Held> held=new LinkedHashMap<>();
    private final Map<UUID,Key> leases=new HashMap<>();
    private final LinkedHashMap<UUID,Key> waiting=new LinkedHashMap<>();
    private int tick=-1,started;
    GeometryChunkLoading(MinecraftServer server){this.server=server;}
    boolean ready(UUID job,ServerLevel level,BlockPos at){
        if(!server.isSameThread())throw new IllegalStateException("GEOMETRY_SERVER_THREAD");
        if(level.isOutsideBuildHeight(at)||!level.getWorldBorder().isWithinBounds(at))throw new IllegalStateException("GEOMETRY_WORLD_BOUNDS");
        var key=new Key(level,at.getX()>>4,at.getZ()>>4);var previous=leases.get(job);
        if(previous!=null&&!previous.equals(key))release(job);
        var existing=held.get(key);
        if(existing!=null){existing.owners.add(job);leases.put(job,key);waiting.remove(job);
            if(existing.loading.isCompletedExceptionally())throw new IllegalStateException("GEOMETRY_CHUNK_LOAD_FAILED");
            return existing.loading.isDone()&&level.getChunkSource().getChunkNow(key.x,key.z)!=null;
        }
        if(level.getChunkSource().getChunkNow(key.x,key.z)!=null){waiting.remove(job);return true;}
        waiting.put(job,key);
        var values=MineAgentRuntimeServices.config(server).snapshot().values();int max=Integer.parseInt(values.getOrDefault(TOTAL,"16")),perTick=Integer.parseInt(values.getOrDefault(PER_TICK,"2"));
        int now=server.getTickCount();if(tick!=now){tick=now;started=0;}
        if(held.size()>=max||started>=perTick||!waiting.keySet().iterator().next().equals(job))return false;
        var lease=new Held();lease.owners.add(job);held.put(key,lease);leases.put(job,key);waiting.remove(job);started++;
        try{lease.loading=level.getChunkSource().addTicketAndLoadWithRadius(MineAgentRegistries.GEOMETRY_TICKET.get(),key.position(),0);}
        catch(RuntimeException failure){release(job);throw failure;}
        return false;
    }
    void release(UUID job){waiting.remove(job);var key=leases.remove(job);if(key==null)return;var lease=held.get(key);if(lease==null)return;lease.owners.remove(job);if(lease.owners.isEmpty()){
        key.level.getChunkSource().removeTicketWithRadius(MineAgentRegistries.GEOMETRY_TICKET.get(),key.position(),0);held.remove(key);
    }}
    Map<String,Object> observation(){return Map.of("leasedChunks",held.size(),"waitingJobs",waiting.size(),"loadsStartedThisTick",started);}
    void close(){for(var id:List.copyOf(leases.keySet()))release(id);waiting.clear();}
}
