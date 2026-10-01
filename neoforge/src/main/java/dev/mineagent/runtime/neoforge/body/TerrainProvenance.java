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
    private boolean ready,failed,closed;private UUID world;
    private final Map<String,UUID> pending=new LinkedHashMap<>();
    private TerrainProvenance(MinecraftServer server){this.server=server;}
    private void start(){
        if(world!=null||closed||!dev.mineagent.runtime.neoforge.WorldIdentityRuntime.ready(server))return;
        world=MineAgentRuntimeServices.worldId(server);UUID scope=world;
        writes=CompletableFuture.supplyAsync(()->{try(var db=new SqliteRuntimeRepository(server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"))){return db.list(scope,SPACE);}catch(Exception e){throw new CompletionException(e);}},IO)
            .thenAccept(rows->server.execute(()->{if(closed||ALL.get(server)!=this)return;for(var row:rows)placed.add(row.recordId());ready=true;}));
        writes.exceptionally(error->{server.execute(()->{if(!closed)failed=true;});return null;});flushPending();
    }
    public static TerrainProvenance get(MinecraftServer server){var history=ALL.computeIfAbsent(server,TerrainProvenance::new);history.start();return history;}
    private static String key(ServerLevel level,BlockPos pos){return level.dimension().identifier()+"/"+pos.asLong();}
    public boolean available(){return ready&&!failed&&!closed&&dev.mineagent.runtime.neoforge.WorldIdentityRuntime.ready(server);}
    public boolean knownPlaced(ServerLevel level,BlockPos pos){return placed.contains(key(level,pos));}
    public static void record(ServerLevel level,BlockPos pos,UUID author){
        var history=get(level.getServer());String key=key(level,pos);if(!history.placed.add(key))return;
        history.pending.put(key,author);history.flushPending();
    }
    private void flushPending(){
        if(world==null||closed||pending.isEmpty()||!dev.mineagent.runtime.neoforge.WorldIdentityRuntime.ready(server))return;
        UUID scope=world;var batch=Map.copyOf(pending);pending.clear();
        writes=writes.thenRunAsync(()->{try(var db=new SqliteRuntimeRepository(server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db"))){for(var row:batch.entrySet()){
            String value="{\"author\":\""+row.getValue()+"\",\"source\":\"PLAYER_OR_AUTHORIZED_TASK\"}";
            if(db.get(scope,SPACE,row.getKey()).isEmpty()&&!db.compareAndSet(scope,SPACE,row.getKey(),0,value,System.currentTimeMillis()).accepted())throw new IllegalStateException("TERRAIN_PROVENANCE_CONFLICT");
        }}catch(Exception error){server.execute(()->{if(!closed)failed=true;});throw new CompletionException(error);}},IO);
    }
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST)
    public static void placed(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event){
        if(event.isCanceled()||!(event.getLevel() instanceof ServerLevel level)||!(event.getEntity() instanceof ServerPlayer player))return;
        if(event instanceof net.neoforged.neoforge.event.level.BlockEvent.EntityMultiPlaceEvent multi)for(var snapshot:multi.getReplacedBlockSnapshots())record(level,snapshot.getPos(),player.getUUID());
        else record(level,event.getPos(),player.getUUID());
    }
    @SubscribeEvent public static void starting(net.neoforged.neoforge.event.server.ServerStartedEvent event){get(event.getServer());}
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){var history=ALL.get(event.getServer());if(history!=null){history.start();history.flushPending();}}
    @SubscribeEvent public static void stopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event){
        var history=ALL.remove(event.getServer());if(history!=null)try{history.closed=true;history.writes.get(10,TimeUnit.SECONDS);}catch(Exception error){dev.mineagent.runtime.neoforge.MineAgentRuntimeMod.LOGGER.warn("Terrain provenance flush incomplete; recovery keeps unknown-source policy",error);}
    }
}
