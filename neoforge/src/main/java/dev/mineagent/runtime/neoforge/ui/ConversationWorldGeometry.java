package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mineagent.runtime.core.geometry.WorldGeometry;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import dev.mineagent.runtime.api.permission.PermissionAction;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** World-edit plans are immutable, owner/Agent bound, one-shot and loaded-chunk only. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class ConversationWorldGeometry {
    private static ExecutorService executor(String name,int queue){return new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(queue),r->{var t=new Thread(r,name);t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());}
    private static final ExecutorService COMPUTE=executor("mineagent-geometry-raster",8),IO=executor("mineagent-geometry-spool",128);
    private static final Map<MinecraftServer,State> LIVE=new IdentityHashMap<>();
    private static final int BATCH=256;
    private interface Work{boolean tick();void stop();}
    private static final class State{final Map<UUID,Plan> plans=new LinkedHashMap<>();final List<Work> jobs=new ArrayList<>();final Set<UUID> planning=new HashSet<>();boolean closed;}
    private static final class Plan {
        final UUID id=UUID.randomUUID(),owner,agent;final ServerLevel level;final long permission;long expires=Long.MAX_VALUE;
        final dev.mineagent.runtime.core.geometry.GeometrySpool spool;final WorldGeometry.StreamSummary geometry;final long generated;
        final List<Map<String,Object>> mismatchExamples=new ArrayList<>();final Map<String,Long> desiredStates=new TreeMap<>();final Map<String,BlockState> parsed=new HashMap<>();
        boolean imported;Map<String,Object> sourceInfo=Map.of();String invalidState="";String status="SAMPLING",error="";long selected,skipped,unchanged,written,matched,mismatched;
        Plan(ServerPlayer p,UUID agent,dev.mineagent.runtime.core.geometry.GeometrySpool spool,WorldGeometry.StreamSummary geometry,long count){owner=p.getUUID();this.agent=agent;level=p.level();permission=revision(p);this.spool=spool;this.geometry=geometry;generated=count;}
    }
    private static State state(MinecraftServer s){return LIVE.computeIfAbsent(s,k->new State());}
    private static long revision(ServerPlayer p){return MineAgentRuntimeServices.permissions(p.level().getServer()).actionRevision(p.getUUID(),PermissionAction.RUN_CODE);}
    private static void keys(JsonNode a,String... names){if(!a.isObject()||!Set.of(names).containsAll(a.properties().stream().map(Map.Entry::getKey).toList()))throw new IllegalArgumentException("GEOMETRY_FIELDS");}
    private static String code(Throwable e){String code="GEOMETRY_FAILED";for(Throwable c=e;c!=null;c=c.getCause())if(c.getMessage()!=null&&c.getMessage().matches("GEOMETRY_[A-Z_0-9]+"))code=c.getMessage();return code;}
    private static void require(boolean yes,String reason){if(!yes)throw new IllegalStateException("GEOMETRY_"+reason);}
    private static boolean current(ServerPlayer p,UUID agent,ServerLevel level,long permission,BooleanSupplier permit){return permit.getAsBoolean()&&p.level()==level&&level.getServer().getPlayerList().getPlayer(p.getUUID())==p&&p.isAlive()&&p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)&&ServerTaskStart.allowed(p,agent)&&revision(p)==permission;}
    private static void location(ServerLevel level,BlockPos pos){require(!level.isOutsideBuildHeight(pos)&&level.getWorldBorder().isWithinBounds(pos),"WORLD_BOUNDS");require(level.hasChunkAt(pos),"CHUNK_NOT_LOADED");}
    private static BlockState parse(ServerPlayer p,String value,boolean imported)throws Exception{
        var reader=new com.mojang.brigadier.StringReader(value);BlockStateParser.BlockResult result;try{result=BlockStateParser.parseForBlock(p.registryAccess().lookupOrThrow(Registries.BLOCK),reader,false);}catch(com.mojang.brigadier.exceptions.CommandSyntaxException invalid){throw new IllegalArgumentException("GEOMETRY_BLOCK_STATE_INVALID",invalid);}require(!reader.canRead(),"BLOCK_STATE_TRAILING_DATA");require(imported||!result.blockState().hasBlockEntity(),"BLOCK_ENTITY_NOT_SUPPORTED");return result.blockState();
    }
    private static BlockState cached(ServerPlayer p,Plan plan,String value)throws Exception{var state=plan.parsed.get(value);if(state==null){try{state=parse(p,value,plan.imported);}catch(Exception e){plan.invalidState=value;throw e;}plan.parsed.put(value,state);}return state;}
    private static BlockState oriented(BlockState b,List<String> ops){for(String op:ops)b=switch(op){case "r1"->b.rotate(Rotation.CLOCKWISE_90);case "r2"->b.rotate(Rotation.CLOCKWISE_180);case "r3"->b.rotate(Rotation.COUNTERCLOCKWISE_90);case "mx"->mirror(b,Mirror.FRONT_BACK);case "mz"->mirror(b,Mirror.LEFT_RIGHT);default->throw new IllegalArgumentException("GEOMETRY_ORIENTATION");};return b;}
    private static BlockState mirror(BlockState before,Mirror mirror){var after=before.mirror(mirror);if(before.getBlock() instanceof StairBlock){var shape=before.getValue(StairBlock.SHAPE);after=after.setValue(StairBlock.SHAPE,switch(shape){case INNER_LEFT->net.minecraft.world.level.block.state.properties.StairsShape.INNER_RIGHT;case INNER_RIGHT->net.minecraft.world.level.block.state.properties.StairsShape.INNER_LEFT;case OUTER_LEFT->net.minecraft.world.level.block.state.properties.StairsShape.OUTER_RIGHT;case OUTER_RIGHT->net.minecraft.world.level.block.state.properties.StairsShape.OUTER_LEFT;default->shape;});}return after;}
    private static void writable(ServerLevel level,BlockPos pos,BlockState after){
        location(level,pos);require(level.getBlockEntity(pos)==null&&!level.getBlockState(pos).hasBlockEntity(),"EXISTING_BLOCK_ENTITY");
        var shape=after.getCollisionShape(level,pos);if(!shape.isEmpty())for(var box:shape.toAabbs())require(level.getEntities((net.minecraft.world.entity.Entity)null,box.move(pos),e->e instanceof LivingEntity&&e.isAlive()).isEmpty(),"LIVING_ENTITY_COLLISION");
    }
    private static <T> CompletableFuture<T> io(Callable<T> task){try{return CompletableFuture.supplyAsync(()->{try{return task.call();}catch(Exception e){throw new CompletionException(e);}},IO);}catch(Exception e){return CompletableFuture.failedFuture(e);}}
    private static void dispose(Plan p){io(()->{p.spool.delete();return null;});}
    private static void retain(State st,Plan plan){while(st.plans.size()>=8){var old=st.plans.values().stream().filter(v->!v.status.equals("PLANNED")&&!v.status.equals("APPLYING")).findFirst();if(old.isEmpty())break;st.plans.remove(old.get().id);dispose(old.get());}st.plans.put(plan.id,plan);}
    public static CompletableFuture<Map<String,Object>> inspect(ServerPlayer p,UUID agent,JsonNode a){
        keys(a,"plan_id","offset");if(!a.has("plan_id"))return CompletableFuture.completedFuture(Map.of("contract",WorldGeometry.CONTRACT,"maxAxis",WorldGeometry.MAX_AXIS,"maxBoundingVolume",2048L*2048*2048,"storage","ASYNC_DISK_SPOOL","cumulativeToolRoundsLimited",false,"cumulativeToolCallsLimited",false,"canEdit",p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)&&ServerTaskStart.allowed(p,agent)));
        Plan plan=owned(p,agent,a);long offset=0;if(a.has("offset")){require(a.get("offset").isIntegralNumber()&&a.get("offset").canConvertToLong()&&a.get("offset").longValue()>=0,"OFFSET");offset=a.get("offset").longValue();}long cursor=offset;var out=new LinkedHashMap<String,Object>(summary(plan));
        return io(()->{var page=plan.spool.page(cursor,33,true);var rows=new ArrayList<Object>();for(var r:page.subList(0,Math.min(page.size(),32)))rows.add(Map.of("position",List.of(r.x(),r.y(),r.z()),"before",r.before(),"after",r.after()));out.put("cells",rows);out.put("nextOffset",page.size()>32?page.get(31).cursor():-1);return out;});
    }
    private static Plan owned(ServerPlayer p,UUID agent,JsonNode a){require(a.path("plan_id").isTextual(),"PLAN_ID");Plan plan=state(p.level().getServer()).plans.get(UUID.fromString(a.path("plan_id").asText()));require(plan!=null&&plan.owner.equals(p.getUUID())&&plan.agent.equals(agent)&&plan.level==p.level(),"PLAN_SCOPE");require(plan.status.equals("APPLYING")||System.currentTimeMillis()<plan.expires,"PLAN_EXPIRED");return plan;}
    private static Map<String,Object> summary(Plan p){
        var out=new LinkedHashMap<String,Object>();out.put("source",p.sourceInfo);out.put("planId",p.id);out.put("status",p.status);out.put("error",p.error);out.put("expiresAt",p.expires);out.put("origin",p.geometry.origin().list());out.put("parts",p.geometry.parts());out.put("generated",p.generated);out.put("selected",p.selected);out.put("maskSkipped",p.skipped);out.put("unchanged",p.unchanged);out.put("written",p.written);out.put("matchedAfterPhysics",p.matched);out.put("mismatchedAfterPhysics",p.mismatched);out.put("mismatchExamples",List.copyOf(p.mismatchExamples));out.put("desiredStates",Map.copyOf(p.desiredStates));out.put("bounds",Map.of("min",p.geometry.min().list(),"max",p.geometry.max().list()));return out;
    }
    private record Raster(dev.mineagent.runtime.core.geometry.GeometrySpool spool,WorldGeometry.StreamSummary geometry,long size){}
    public static CompletableFuture<Map<String,Object>> plan(ServerPlayer p,UUID agent,JsonNode a,BooleanSupplier permit){
        keys(a,"source");require(a.path("source").isTextual()&&a.path("source").asText().length()<=14000,"SOURCE");var s=p.level().getServer();State st=state(s);var level=p.level();long permission=revision(p);require(current(p,agent,level,permission,permit),"PERMISSION");
        require(st.plans.values().stream().filter(v->v.status.equals("PLANNED")||v.status.equals("APPLYING")).count()+st.planning.size()<8&&st.plans.values().stream().filter(v->v.owner.equals(p.getUUID())&&v.status.equals("PLANNED")).count()<2&&!st.planning.contains(p.getUUID()),"PLAN_CAPACITY");
        st.planning.add(p.getUUID());var result=new CompletableFuture<Map<String,Object>>();var valid=new java.util.concurrent.atomic.AtomicBoolean(true);
        var file=s.getServerDirectory().resolve("mineagent-runtime-data/geometry").resolve(UUID.randomUUID()+".db");
        CompletableFuture<Raster> raster;try{raster=CompletableFuture.supplyAsync(()->{dev.mineagent.runtime.core.geometry.GeometrySpool spool=null;try{spool=dev.mineagent.runtime.core.geometry.GeometrySpool.create(file);var geometry=spool.generate(a.get("source").asText(),valid::get);return new Raster(spool,geometry,spool.size());}catch(Exception e){if(spool!=null)try{spool.delete();}catch(Exception ignored){}throw new CompletionException(e);}},COMPUTE);}catch(Exception e){st.planning.remove(p.getUUID());return CompletableFuture.completedFuture(Map.of("status","REJECTED","error","GEOMETRY_COMPUTE_BUSY"));}
        st.jobs.add(new Work(){public boolean tick(){if(!current(p,agent,level,permission,permit))valid.set(false);if(!raster.isDone())return false;st.planning.remove(p.getUUID());try{Raster r=raster.join();Plan plan=new Plan(p,agent,r.spool,r.geometry,r.size);if(!valid.get()){dispose(plan);throw new IllegalStateException("GEOMETRY_CONTEXT_CHANGED");}st.planning.add(p.getUUID());sample(p,agent,st,plan,permit,result);}catch(Exception e){result.complete(Map.of("status","REJECTED","error",code(e)));}return true;}
            public void stop(){valid.set(false);raster.whenComplete((r,e)->{if(r!=null)io(()->{r.spool.delete();return null;});});result.complete(Map.of("status","REJECTED","error","GEOMETRY_SERVER_STOPPED"));}});return result;
    }
    public static CompletableFuture<Map<String,Object>> planImported(ServerPlayer p,UUID agent,dev.mineagent.runtime.core.geometry.GeometrySpool.Producer<WorldGeometry.StreamSummary> source,Map<String,Object> info,BooleanSupplier permit){
        var server=p.level().getServer();var level=p.level();long permission=revision(p);require(current(p,agent,level,permission,permit),"PERMISSION");State st=state(server);require(!st.planning.contains(p.getUUID())&&st.plans.values().stream().filter(v->v.status.equals("PLANNED")||v.status.equals("APPLYING")).count()+st.planning.size()<8,"PLAN_CAPACITY");st.planning.add(p.getUUID());var result=new CompletableFuture<Map<String,Object>>();var valid=new java.util.concurrent.atomic.AtomicBoolean(true);
        var file=server.getServerDirectory().resolve("mineagent-runtime-data/geometry").resolve(UUID.randomUUID()+".db");CompletableFuture<Raster> raster;
        try{raster=CompletableFuture.supplyAsync(()->{dev.mineagent.runtime.core.geometry.GeometrySpool spool=null;try{spool=dev.mineagent.runtime.core.geometry.GeometrySpool.create(file);var geometry=spool.generateWith(source,valid::get);return new Raster(spool,geometry,spool.size());}catch(Exception e){if(spool!=null)try{spool.delete();}catch(Exception ignored){}throw new CompletionException(e);}},COMPUTE);}catch(Exception e){st.planning.remove(p.getUUID());return CompletableFuture.completedFuture(Map.of("status","REJECTED","error","GEOMETRY_COMPUTE_BUSY"));}
        st.jobs.add(new Work(){public boolean tick(){if(!current(p,agent,level,permission,permit))valid.set(false);if(!raster.isDone())return false;st.planning.remove(p.getUUID());try{var r=raster.join();var plan=new Plan(p,agent,r.spool,r.geometry,r.size);plan.imported=true;plan.sourceInfo=Map.copyOf(info);if(!valid.get()){dispose(plan);throw new IllegalStateException("GEOMETRY_CONTEXT_CHANGED");}st.planning.add(p.getUUID());sample(p,agent,st,plan,permit,result);}catch(Exception e){String reason=code(e);for(Throwable c=e;c!=null;c=c.getCause())if(c.getMessage()!=null&&c.getMessage().matches("BUILDING_[A-Z_0-9]+"))reason=c.getMessage();result.complete(Map.of("status","REJECTED","error",reason));}return true;}
            public void stop(){valid.set(false);raster.whenComplete((r,e)->{if(r!=null)io(()->{r.spool.delete();return null;});});result.complete(Map.of("status","REJECTED","error","GEOMETRY_SERVER_STOPPED"));}});return result;
    }
    private static void sample(ServerPlayer p,UUID agent,State st,Plan plan,BooleanSupplier permit,CompletableFuture<Map<String,Object>> result){
        var level=plan.level;st.jobs.add(new Work(){CompletableFuture<List<dev.mineagent.runtime.core.geometry.GeometrySpool.Row>> pending=io(()->plan.spool.page(0,BATCH,false));
            public boolean tick(){try{require(current(p,agent,level,plan.permission,permit),"CONTEXT_CHANGED");if(!pending.isDone())return false;var rows=pending.join();
                if(rows.isEmpty()){plan.status="PLANNED";plan.expires=System.currentTimeMillis()+600000;retain(st,plan);st.planning.remove(p.getUUID());result.complete(summary(plan));return true;}
                var snapshots=new ArrayList<dev.mineagent.runtime.core.geometry.GeometrySpool.Snapshot>();for(var r:rows){var pos=new BlockPos(r.x(),r.y(),r.z());location(level,pos);BlockState before=level.getBlockState(pos),after=oriented(cached(p,plan,r.state()),r.orientation());boolean match=plan.geometry.replace().isEmpty();for(String filter:plan.geometry.replace()){BlockState mask=cached(p,plan,filter);if(filter.contains("[")?before==mask:before.getBlock()==mask.getBlock())match=true;}
                    if(!match)plan.skipped++;else{if(before==after)plan.unchanged++;else writable(level,pos,after);plan.selected++;plan.desiredStates.merge(BlockStateParser.serialize(after),1L,Long::sum);}
                    snapshots.add(new dev.mineagent.runtime.core.geometry.GeometrySpool.Snapshot(r.cursor(),BlockStateParser.serialize(before),BlockStateParser.serialize(after),match));}
                long cursor=rows.getLast().cursor();pending=io(()->{plan.spool.snapshots(snapshots);return plan.spool.page(cursor,BATCH,false);});return false;
            }catch(Exception e){st.planning.remove(p.getUUID());result.complete(Map.of("status","REJECTED","error",code(e),"invalidState",plan.invalidState));dispose(plan);return true;}}
            public void stop(){st.planning.remove(p.getUUID());result.complete(Map.of("status","REJECTED","error","GEOMETRY_SERVER_STOPPED"));dispose(plan);}});
    }
    public static CompletableFuture<Map<String,Object>> apply(ServerPlayer p,UUID agent,JsonNode a,BooleanSupplier permit){
        keys(a,"plan_id");Plan plan=owned(p,agent,a);require(plan.status.equals("PLANNED"),"PLAN_ALREADY_CONSUMED");require(current(p,agent,plan.level,plan.permission,permit),"CONTEXT_CHANGED");plan.status="APPLYING";var result=new CompletableFuture<Map<String,Object>>();var level=plan.level;
        state(level.getServer()).jobs.add(new Work(){int phase;final int verifyPhase=plan.imported?3:2;long verifyTick;CompletableFuture<List<dev.mineagent.runtime.core.geometry.GeometrySpool.Row>> pending=io(()->plan.spool.page(0,BATCH,true));
            private boolean end(String error){plan.expires=System.currentTimeMillis()+600000;plan.error=error;plan.status=plan.written>0?"PARTIAL":"REJECTED";result.complete(summary(plan));return true;}
            public boolean tick(){try{
                require(current(p,agent,level,plan.permission,permit),"CONTEXT_CHANGED");if(phase==verifyPhase&&level.getGameTime()<verifyTick||!pending.isDone())return false;var rows=pending.join();
                if(rows.isEmpty()){if(phase==verifyPhase){plan.expires=System.currentTimeMillis()+600000;plan.status=plan.mismatched==0?"APPLIED":"PARTIAL";if(plan.mismatched>0)plan.error="GEOMETRY_PHYSICS_CHANGED_STATE";result.complete(summary(plan));return true;}phase++;if(phase==verifyPhase)verifyTick=level.getGameTime()+4;pending=io(()->plan.spool.page(0,BATCH,true));return false;}
                for(var r:rows){BlockPos pos=new BlockPos(r.x(),r.y(),r.z());location(level,pos);BlockState before=cached(p,plan,r.before()),after=cached(p,plan,r.after());
                    if(phase<2){require(level.getBlockState(pos)==before,"STATE_CHANGED");if(before!=after)writable(level,pos,after);if(phase==1&&before!=after){boolean changed=level.setBlock(pos,after,plan.imported?Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE:Block.UPDATE_ALL);if(level.getBlockState(pos)!=before)plan.written++;require(changed,"WRITE_REJECTED");}}
                    else if(plan.imported&&phase==2){var state=level.getBlockState(pos);state.updateNeighbourShapes(level,pos,Block.UPDATE_ALL);level.updateNeighborsAt(pos,state.getBlock());}
                    else if(level.getBlockState(pos)==after)plan.matched++;else{plan.mismatched++;if(plan.mismatchExamples.size()<16)plan.mismatchExamples.add(Map.of("position",List.of(pos.getX(),pos.getY(),pos.getZ()),"expected",BlockStateParser.serialize(after),"observed",BlockStateParser.serialize(level.getBlockState(pos))));}
                }
                long cursor=rows.getLast().cursor();pending=io(()->plan.spool.page(cursor,BATCH,true));return false;
            }catch(Exception e){return end(code(e));}}
            public void stop(){end("GEOMETRY_SERVER_STOPPED");}
        });return result;
    }
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e){State s=LIVE.get(e.getServer());if(s==null)return;for(Work w:List.copyOf(s.jobs))if(w.tick())s.jobs.remove(w);for(var p:List.copyOf(s.plans.values()))if(!p.status.equals("APPLYING")&&System.currentTimeMillis()>=p.expires){s.plans.remove(p.id);dispose(p);}}
    @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppedEvent e){State s=LIVE.remove(e.getServer());if(s!=null){s.closed=true;for(Work w:List.copyOf(s.jobs))w.stop();for(var p:s.plans.values())dispose(p);}}
    private ConversationWorldGeometry(){}
}
