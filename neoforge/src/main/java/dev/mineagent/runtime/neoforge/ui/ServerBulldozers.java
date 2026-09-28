package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.geometry.BulldozerSpec;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit bounded continuous clearing; game writes remain on the server tick, persistence stays off tick. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class ServerBulldozers {
    private static final ObjectMapper JSON=new ObjectMapper();private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
    private static final String SPACE="bulldozer_definitions_v1";
    private record Key(UUID owner,UUID agent,String id){}
    private record Saved(UUID owner,UUID agent,BulldozerSpec spec){}
    private static final class Head {
        final Key key;Saved saved;long revision,cleared,examined,lastStep=Long.MIN_VALUE;String status="PAUSED_RESTART",error="";boolean active,busy,logging;BlockPos anchor;int heading,cursor;final Map<String,Set<BlockPos>> blocked=new LinkedHashMap<>();
        Head(Key key,Saved saved,long revision){this.key=key;this.saved=saved;this.revision=revision;}
    }
    private static final Map<MinecraftServer,Map<Key,Head>> LIVE=new IdentityHashMap<>();
    private static Path database(MinecraftServer s){return s.getServerDirectory().resolve("mineagent-runtime-data/runtime.db");}
    private static String key(Key key){return key.owner+"/"+key.agent+"/"+key.id;}
    private static void authorize(ServerPlayer p,UUID agent,boolean write){if(!ServerTaskStart.allowed(p,agent)||write&&!p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))throw new SecurityException("BULLDOZER_PERMISSION");}
    private static CompletableFuture<Map<Key,Head>> load(ServerPlayer p,UUID agent){
        var s=p.level().getServer();var world=MineAgentRuntimeServices.worldId(s);var owner=p.getUUID();var out=new CompletableFuture<Map<Key,Head>>();
        CompletableFuture.supplyAsync(()->{try(var db=new SqliteRuntimeRepository(database(s))){var result=new ArrayList<Head>();for(var row:db.list(world,SPACE)){var value=JSON.readValue(row.payload(),Saved.class);if(value.owner.equals(owner)&&value.agent.equals(agent))result.add(new Head(new Key(owner,agent,value.spec.id()),value,row.revision()));}return result;}catch(Exception e){throw new CompletionException(e);}},IO).whenComplete((values,error)->s.execute(()->{if(error!=null){out.completeExceptionally(error);return;}var map=LIVE.computeIfAbsent(s,k->new LinkedHashMap<>());for(var value:values)map.compute(value.key,(key,old)->old==null||!old.busy&&value.revision>old.revision?value:old);out.complete(map);}));return out;
    }
    public static CompletableFuture<Map<String,Object>> inspect(ServerPlayer p,UUID agent,JsonNode args){authorize(p,agent,false);return load(p,agent).thenApply(map->Map.of("status","OBSERVED","bulldozers",map.values().stream().filter(h->h.key.owner.equals(p.getUUID())&&h.key.agent.equals(agent)&&(!args.has("id")||h.key.id.equals(args.path("id").asText()))).map(ServerBulldozers::view).toList()));}
    private static Map<String,Object> view(Head h){var blocked=new LinkedHashMap<String,Integer>();h.blocked.forEach((reason,positions)->blocked.put(reason,positions.size()));return Map.of("id",h.key.id,"revision",h.revision,"definition",h.saved.spec,"state",h.status,"active",h.active,"clearedThisRun",h.cleared,"examinedThisRun",h.examined,"blockedPositions",blocked,"error",h.error);}
    public static CompletableFuture<Map<String,Object>> change(ServerPlayer p,UUID agent,JsonNode args,boolean define){
        authorize(p,agent,true);var s=p.level().getServer();var level=p.level();var owner=p.getUUID();var world=MineAgentRuntimeServices.worldId(s);String id=args.path("id").asText();if(!id.matches("[A-Za-z][A-Za-z0-9_-]{0,95}"))throw new IllegalArgumentException("BULLDOZER_ID");long expected=args.path("expected_revision").asLong(-1);if(!args.path("expected_revision").isIntegralNumber()||!args.path("expected_revision").canConvertToLong()||expected<0)throw new IllegalArgumentException("BULLDOZER_REVISION");var key=new Key(owner,agent,id);var result=new CompletableFuture<Map<String,Object>>();
        load(p,agent).whenComplete((map,error)->s.execute(()->{try{
            if(error!=null)throw new CompletionException(error);authorize(p,agent,true);if(p.level()!=level||s.getPlayerList().getPlayer(owner)!=p)throw new IllegalStateException("BULLDOZER_CONTEXT_CHANGED");
            var old=map.get(key);if(expected!=(old==null?0:old.revision)||old!=null&&old.busy)throw new IllegalStateException("BULLDOZER_STALE_OR_BUSY");
            String action=define?"start":args.path("action").asText();if(!Set.of("start","pause","resume","stop").contains(action))throw new IllegalArgumentException("BULLDOZER_ACTION");
            var spec=define?BulldozerSpec.parse(args):Objects.requireNonNull(old,"BULLDOZER_NOT_FOUND").saved.spec;var saved=new Saved(owner,agent,spec);boolean active=action.equals("start")||action.equals("resume");
            if(active){if(!spec.dimension().equals(level.dimension().identifier().toString()))throw new IllegalStateException("BULLDOZER_DIMENSION");target(p,agent,spec);}
            if(old!=null){old.active=false;old.busy=true;old.status="PAUSED";}
            CompletableFuture.supplyAsync(()->{try(var db=new SqliteRuntimeRepository(database(s))){var receipt=db.compareAndSet(world,SPACE,key(key),expected,JSON.writeValueAsString(saved),System.currentTimeMillis());if(!receipt.accepted())throw new IllegalStateException("BULLDOZER_STALE");return true;}catch(Exception failure){throw new CompletionException(failure);}},IO).whenComplete((written,failure)->s.execute(()->{
                if(old!=null)old.busy=false;if(failure!=null){result.completeExceptionally(failure);return;}var head=new Head(key,saved,expected+1);head.active=active&&p.level()==level&&s.getPlayerList().getPlayer(owner)==p&&ServerTaskStart.allowed(p,agent)&&p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);head.status=head.active?"RUNNING":action.equals("stop")?"STOPPED":"PAUSED";map.put(key,head);result.complete(Map.of("status",head.active?"STARTED":"APPLIED","bulldozer",view(head),"worldCompletion","INSPECT_ACTUAL_PROGRESS"));
            }));
        }catch(Exception failure){result.completeExceptionally(failure);}}));return result;
    }
    private static Entity target(ServerPlayer p,UUID agent,BulldozerSpec spec){
        Entity entity=spec.target().equals("$viewer")?p:spec.target().equals("$agent")?MineAgentRuntimeServices.bodies(p.level().getServer()).body(agent).orElseThrow(()->new IllegalStateException("BULLDOZER_TARGET_MISSING")):p.level().getEntity(UUID.fromString(spec.target()));
        if(entity==null||!entity.isAlive()||entity.level()!=p.level())throw new IllegalStateException("BULLDOZER_TARGET_MISSING");
        if(entity!=p&&!spec.target().equals("$agent")&&!(entity instanceof dev.mineagent.runtime.neoforge.content.RuntimeCreatureEntity creature&&p.getUUID().equals(creature.owner()))&&!entity.getPersistentData().getStringOr("mineagent_native_owner","").equals(p.getUUID().toString()))throw new SecurityException("BULLDOZER_TARGET_NOT_OWNED");return entity;
    }
    private static void blocked(Head h,String reason,BlockPos pos){var values=h.blocked.computeIfAbsent(reason,k->new HashSet<>());if(values.size()<512)values.add(pos.immutable());}
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        var s=event.getServer();var map=LIVE.get(s);if(map==null)return;int budget=512;long deadline=System.nanoTime()+3_000_000;
        for(var h:map.values().stream().sorted(Comparator.comparingLong(h->h.lastStep)).toList()){
            if(!h.active||h.busy||h.logging)continue;h.lastStep=s.getTickCount();var p=s.getPlayerList().getPlayer(h.key.owner);var edits=new ArrayList<Map<String,Object>>();
            try{
                if(p==null||!p.isAlive())throw new IllegalStateException("BULLDOZER_OWNER_UNAVAILABLE");authorize(p,h.key.agent,true);var spec=h.saved.spec;if(!spec.dimension().equals(p.level().dimension().identifier().toString()))throw new IllegalStateException("BULLDOZER_DIMENSION");var entity=target(p,h.key.agent,spec);var anchor=entity.blockPosition();int heading=BulldozerSpec.heading(entity.getYRot());
                if(!spec.contains(anchor.getX(),anchor.getY(),anchor.getZ()))throw new IllegalStateException("BULLDOZER_OUTSIDE_BOUNDS");if(!anchor.equals(h.anchor)||heading!=h.heading){h.anchor=anchor;h.heading=heading;h.cursor=0;}
                int count=Math.min(128,spec.volume());while(count-->0&&budget-->0&&System.nanoTime()<deadline){
                    int[] point=spec.cell(anchor.getX(),anchor.getY(),anchor.getZ(),heading,h.cursor++);if(h.cursor>=spec.volume())h.cursor=0;var pos=new BlockPos(point[0],point[1],point[2]);if(!spec.contains(point[0],point[1],point[2]))continue;h.examined++;
                    if(!p.level().isInWorldBounds(pos)||!p.level().getChunkSource().hasChunk(point[0]>>4,point[2]>>4)){blocked(h,"UNLOADED_OR_HEIGHT",pos);continue;}
                    var before=p.level().getBlockState(pos);if(before.isAir())continue;if(!spec.blockEntities()&&before.hasBlockEntity()){blocked(h,"BLOCK_ENTITY",pos);continue;}if(!spec.unbreakable()&&before.getDestroySpeed(p.level(),pos)<0){blocked(h,"UNBREAKABLE",pos);continue;}
                    if(!p.level().mayInteract(p,pos)||s.isUnderSpawnProtection(p.level(),pos,p)){blocked(h,"PROTECTED",pos);continue;}
                    var breaking=new net.neoforged.neoforge.event.level.block.BreakBlockEvent(p.level(),pos,before,p);net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(breaking);if(breaking.isCanceled()){blocked(h,"BREAK_EVENT_CANCELLED",pos);continue;}
                    if(p.level().getBlockState(pos)!=before){blocked(h,"STATE_CHANGED",pos);continue;}
                    boolean changed=p.level().destroyBlock(pos,spec.drops(),entity,512);var after=p.level().getBlockState(pos);if(changed&&after.isAir()){h.cleared++;edits.add(Map.of("position",List.of(point[0],point[1],point[2]),"before",net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(before),"after","minecraft:air"));}else blocked(h,"WRITE_NOT_CLEARED",pos);
                }
            }catch(Exception failure){h.active=false;h.status="PAUSED";h.error=Objects.toString(failure.getMessage(),"BULLDOZER_FAILED");}
            if(!edits.isEmpty()){
                h.logging=true;var world=MineAgentRuntimeServices.worldId(s);long tick=s.getTickCount();var journal=s.getServerDirectory().resolve("mineagent-runtime-data/bulldozers").resolve(world.toString()).resolve(h.key.owner.toString()).resolve(h.key.agent.toString()).resolve(h.key.id+".jsonl");
                CompletableFuture.runAsync(()->{try{Files.createDirectories(journal.getParent());Files.writeString(journal,JSON.writeValueAsString(Map.of("revision",h.revision,"tick",tick,"edits",edits))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception failure){throw new CompletionException(failure);}},IO).whenComplete((ok,error)->s.execute(()->{h.logging=false;if(error!=null){h.active=false;h.status="UNKNOWN";h.error="BULLDOZER_JOURNAL_FAILED_INSPECT_WORLD";}}));
            }
            if(budget<=0||System.nanoTime()>=deadline)break;
        }
    }
    @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppedEvent event){var map=LIVE.remove(event.getServer());if(map!=null)map.values().forEach(h->h.active=false);}
    private ServerBulldozers(){}
}
