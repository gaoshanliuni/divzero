package dev.mineagent.runtime.neoforge.body;

import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.*;
import java.util.concurrent.*;

/** Durable evidence of placed blocks. Absence means UNKNOWN, never proof of natural generation. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class TerrainProvenance {
    private static final String SPACE="placed_terrain_v1";
    private static final Map<MinecraftServer,TerrainProvenance> ALL=new IdentityHashMap<>();
    private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
    private final MinecraftServer server;private final Set<String> placed=new HashSet<>();
    private CompletableFuture<Void> writes=CompletableFuture.completedFuture(null);
    private boolean ready,failed;
    private TerrainProvenance(MinecraftServer server){
        this.server=server;var world=MineAgentRuntimeServices.worldId(server);
        writes=CompletableFuture.supplyAsync(()->{try(var db=new SqliteRuntimeRepository(server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"))){return db.list(world,SPACE);}catch(Exception e){throw new CompletionException(e);}},IO)
                .thenAccept(rows->server.execute(()->{for(var row:rows)placed.add(row.recordId());ready=true;}));
        writes.exceptionally(error->{server.execute(()->failed=true);return null;});
    }
    public static TerrainProvenance get(MinecraftServer server){return ALL.computeIfAbsent(server,TerrainProvenance::new);}
    private static String key(ServerLevel level,BlockPos pos){return level.dimension().identifier()+"/"+pos.asLong();}
    public boolean available(){return ready&&!failed;}
    public boolean knownPlaced(ServerLevel level,BlockPos pos){return placed.contains(key(level,pos));}
    public static void record(ServerLevel level,BlockPos pos,UUID author){
        var history=get(level.getServer());String key=key(level,pos);if(!history.placed.add(key))return;
        var world=MineAgentRuntimeServices.worldId(level.getServer());String value="{\"author\":\""+author+"\",\"source\":\"PLAYER_OR_AUTHORIZED_TASK\"}";
        history.writes=history.writes.thenRunAsync(()->{try(var db=new SqliteRuntimeRepository(history.server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"))){var old=db.get(world,SPACE,key);if(old.isEmpty()&&!db.compareAndSet(world,SPACE,key,0,value,System.currentTimeMillis()).accepted())throw new IllegalStateException("TERRAIN_PROVENANCE_CONFLICT");}catch(Exception error){history.server.execute(()->history.failed=true);throw new CompletionException(error);}},IO);
    }
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST)
    public static void placed(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event){
        if(event.isCanceled()||!(event.getLevel() instanceof ServerLevel level)||!(event.getEntity() instanceof ServerPlayer player))return;
        if(event instanceof net.neoforged.neoforge.event.level.BlockEvent.EntityMultiPlaceEvent multi)for(var snapshot:multi.getReplacedBlockSnapshots())record(level,snapshot.getPos(),player.getUUID());
        else record(level,event.getPos(),player.getUUID());
    }
    @SubscribeEvent public static void starting(net.neoforged.neoforge.event.server.ServerStartedEvent event){get(event.getServer());}
    @SubscribeEvent public static void stopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event){
        var history=ALL.remove(event.getServer());if(history!=null)try{history.writes.get(10,TimeUnit.SECONDS);}catch(Exception error){dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("Terrain provenance flush incomplete; recovery keeps unknown-source policy",error);}
    }
}
