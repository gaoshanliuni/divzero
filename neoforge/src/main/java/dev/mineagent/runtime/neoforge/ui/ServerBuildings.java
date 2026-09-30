package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.building.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.task.ServerTaskStart;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Owner-scoped construction service. Geometry and journal IO are asynchronous; native reads/writes stay on ticks. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class ServerBuildings {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
    private record Key(UUID owner,UUID agent,String id){}
    private static final Map<MinecraftServer,Map<Key,Handle>> LIVE=new IdentityHashMap<>();
    private static final class Handle {
        final Key key;final CompletableFuture<ConstructionLedger> opened;Work job;boolean busy,closed;
        Handle(Key key,Path directory,ConstructionCatalog.Scope scope){this.key=key;opened=io(()->{
            var ledger=new ConstructionLedger(ConstructionCatalog.register(directory,scope,key.id));ledger.bind(scope,key.id);var h=ledger.head();
            if(Set.of("PREPARING","APPLYING").contains(h.status())){
                if(h.phase().equals("RESERVED"))ledger.fail("UNKNOWN","PROCESS_STOPPED_WITH_RESERVED_BATCH");
                else ledger.progress("PAUSED",h.phase(),h.cursor(),h.mutationTick());
            }return ledger;
        });}
    }
    interface Work{boolean tick();void pause();CompletableFuture<?> pending();default boolean completed(){return false;}}
    private static void retireCompleted(Handle handle){if(handle.job!=null&&handle.job.completed()){handle.job=null;handle.busy=false;}}
    private static <T> CompletableFuture<T> io(Callable<T> action){return CompletableFuture.supplyAsync(()->{try{return action.call();}catch(Exception failure){throw new CompletionException(failure);}},IO);}
    private static void require(boolean allowed,String code){if(!allowed)throw new IllegalStateException("BUILDING_"+code);}
    private static String id(JsonNode args){String value=args.path("id").asText();if(!value.matches("[A-Za-z][A-Za-z0-9_-]{0,95}"))throw new IllegalArgumentException("BUILDING_ID");return value;}
    private static long revision(JsonNode args){var n=args.path("revision");if(!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<0)throw new IllegalArgumentException("BUILDING_REVISION");return n.longValue();}
    private static Path directory(ServerPlayer player,UUID agent){var s=player.level().getServer();return s.getServerDirectory().resolve("mineagent-runtime-data/buildings");}
    private static Handle handle(ServerPlayer player,UUID agent,String id){var key=new Key(player.getUUID(),agent,id);return LIVE.computeIfAbsent(player.level().getServer(),s->new HashMap<>()).computeIfAbsent(key,k->new Handle(k,directory(player,agent),new ConstructionCatalog.Scope(MineAgentRuntimeServices.worldId(player.level().getServer()),player.getUUID(),agent)));}
    static void authorize(ServerPlayer p,UUID agent,boolean mutate){require(p.level().getServer().isSameThread()&&ServerTaskStart.allowed(p,agent),"PERMISSION");if(mutate)require(p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),"GAME_PERMISSION");}
    public static int pauseForAgent(ServerPlayer p,UUID agent){var handles=LIVE.get(p.level().getServer());if(handles==null||!ServerTaskStart.allowed(p,agent))return 0;int count=0;for(var entry:handles.entrySet())if(entry.getKey().owner.equals(p.getUUID())&&entry.getKey().agent.equals(agent)&&entry.getValue().job!=null&&!entry.getValue().job.completed()){entry.getValue().job.pause();count++;}return count;}
    public static CompletableFuture<Map<String,Object>> inspectUi(ServerPlayer player,UUID agent,JsonNode args){
        int offset=args.path("offset").asInt(0);var geometry=args.has("id")?Map.<String,Object>of():ConversationWorldGeometry.uiPlans(player,agent,offset);
        return inspect(player,agent,args).thenApply(value->{try{var result=new LinkedHashMap<String,Object>(BuildingUiDocuments.summary(value,offset));if(!args.has("id")){result.put("geometryPlans",geometry.get("items"));result.put("more",Boolean.TRUE.equals(result.get("more"))||Boolean.TRUE.equals(geometry.get("more")));result.put("nextOffset",offset+16);}return result;}catch(Exception error){throw new CompletionException(error);}});
    }
    public static CompletableFuture<Map<String,Object>> document(ServerPlayer player,UUID agent,JsonNode args){
        authorize(player,agent,false);var handle=handle(player,agent,id(args));long revision=revision(args);String section=args.path("section").asText(),expected=args.path("sha256").asText();int offset=args.path("offset").asInt(0);
        return handle.opened.thenCompose(ledger->io(()->{require(ledger.head().revision()==revision,"STALE_REVISION");String source=switch(section){case "design"->ledger.design(revision).source();case "report"->ledger.head().report();case "history"->JSON.writeValueAsString(ledger.history(args.path("historyOffset").asInt(0)));default->throw new IllegalArgumentException("BUILDING_DOCUMENT_SECTION");};return BuildingUiDocuments.part(source,offset,expected);}));
    }
    private static Map<String,Object> view(Handle handle,ConstructionLedger ledger,int offset)throws Exception {
        var h=ledger.head();var out=new LinkedHashMap<String,Object>();out.put("id",handle.key.id);out.put("revision",h.revision());out.put("activeRevision",h.activeRevision());out.put("status",h.status());out.put("operation",h.operation());out.put("phase",h.phase());out.put("cursor",h.cursor());out.put("verification",h.status().equals("VERIFIED")?"VERIFIED":"UNVERIFIED");out.put("report",h.report());
        if(h.revision()>0){out.put("design",JSON.readTree(ledger.design(h.revision()).source()));out.put("steps",ledger.steps(h.revision()));out.put("history",ledger.history(offset));}return out;
    }
    public static CompletableFuture<Map<String,Object>> inspect(ServerPlayer p,UUID agent,JsonNode args){
        authorize(p,agent,false);int offset=args.path("offset").asInt(0);require(offset>=0,"OFFSET");
        if(!args.has("id")){
            Path directory=directory(p,agent);var scope=new ConstructionCatalog.Scope(MineAgentRuntimeServices.worldId(p.level().getServer()),p.getUUID(),agent);return io(()->{
                if(!Files.isDirectory(directory))return Map.of("status","OBSERVED","buildings",List.of(),"contract",contract());
                var names=ConstructionCatalog.list(directory,scope,offset);var items=new ArrayList<Object>();for(var entry:names.stream().limit(16).toList()){
                    Path path=directory.resolve(entry.filename());if(!Files.exists(path))continue;
                    try(var ledger=new ConstructionLedger(path)){ledger.bind(scope,entry.id());var head=ledger.head();if(head.revision()>0)items.add(Map.of("id",entry.id(),"name",ledger.design(head.revision()).name(),"revision",head.revision(),"activeRevision",head.activeRevision(),"status",head.status()));}
                }return Map.of("status","OBSERVED","buildings",items,"nextOffset",offset+Math.min(16,names.size()),"more",names.size()>16,"contract",contract());
            });
        }
        var handle=handle(p,agent,id(args));return handle.opened.thenCompose(ledger->io(()->view(handle,ledger,offset)));
    }
    private static Map<String,Object> contract(){return Map.of("definition",BuildingDesign.CONTRACT,"minimalExample",BuildingDesign.MINIMAL_EXAMPLE,"source","BuildingDesign JSON: id/name/dimension/origin/templates/components/checks. Component parts use inspect_world_geometry geometry; reuse templates with transforms and stable IDs.","defaultHouse","Use box mode shell/walls or polygon mode walls with thickness, holes and optional caps. Interior air is not emitted. Explicit subtraction remains possible.","checks","Required named component checks: states(expected full state), clearance(min/max), path(adjacent relative standing cells, headroom), bounds(exact component min/max), support(allow_floating only when intended). All coordinates are relative to design origin and constrained to its actual footprint bounds.","flow",List.of("inspect_buildings","plan_building","apply_building","verify_building","local revision if verification fails"),"control",List.of("pause","resume","undo","redo","recover"),"unknown","recover only observes before/after states, then explicit undo can roll back matching cells. It does not replay an uncertain write.");}
    public static CompletableFuture<Map<String,Object>> plan(ServerPlayer p,UUID agent,JsonNode args,BooleanSupplier permit){
        authorize(p,agent,true);require(permit.getAsBoolean(),"CANCELLED");final BuildingDesign design;
        try{design=BuildingDesign.parse(args.path("source").asText());require(!design.checks().isMissingNode()&&!design.checks().isEmpty(),"CHECKS_REQUIRED");}
        catch(IllegalArgumentException|IllegalStateException invalid){return CompletableFuture.completedFuture(Map.of("status","REJECTED","error",Objects.toString(invalid.getMessage(),"BUILDING_DEFINITION_INVALID"),"fieldIssues",BuildingDesign.fieldIssues(args.path("source").asText()),"contract",contract(),"planCommitted",false,"worldModified",false,"retryGuidance","Use the exact contract and fieldIssues paths. This request made no world or ledger changes. Do not guess field names or request access to local program files."));}
        require(design.dimension().equals(p.level().dimension().identifier().toString()),"DIMENSION");var h=handle(p,agent,design.id());retireCompleted(h);require(h.job==null&&!h.busy,"BUSY");h.busy=true;
        var server=p.level().getServer();var level=p.level();var valid=new java.util.concurrent.atomic.AtomicBoolean(true);long generation=MineAgentRuntimeServices.permissions(server).actionRevision(p.getUUID(),PermissionAction.RUN_CODE);
        var future=h.opened.thenCompose(ledger->io(()->{
            var before=ledger.head();
            try{ledger.plan(revision(args),design.source(),valid::get);return view(h,ledger,0);}
            catch(IllegalArgumentException|IllegalStateException failure){
                // Only a confirmed unchanged ledger after the planning transaction is a known rejection.
                // IO/commit uncertainty still propagates to the tool journal as UNKNOWN, without replay.
                if(!before.equals(ledger.head()))throw failure;Throwable cause=failure;while(cause.getCause()!=null)cause=cause.getCause();
                if(!(cause instanceof IllegalArgumentException||cause instanceof IllegalStateException))throw failure;
                String error=Objects.toString(cause.getMessage(),"BUILDING_PLAN_REJECTED");
                return Map.<String,Object>of("id",design.id(),"status","REJECTED","error",error,"revision",before.revision(),"persistentStatus",before.status(),"planCommitted",false,"worldModified",false,"retryGuidance","Correct the definition and explicitly submit a new plan with the unchanged revision.");
            }
        }));
        h.job=new Work(){public boolean completed(){return future.isDone();}public boolean tick(){if(!permit.getAsBoolean()||p.level()!=level||server.getPlayerList().getPlayer(p.getUUID())!=p||!ServerTaskStart.allowed(p,agent)||MineAgentRuntimeServices.permissions(server).actionRevision(p.getUUID(),PermissionAction.RUN_CODE)!=generation)valid.set(false);return future.isDone();}public void pause(){valid.set(false);}public CompletableFuture<?> pending(){return future;}};
        return future.whenComplete((v,e)->server.execute(()->h.busy=false));
    }
    public static CompletableFuture<Map<String,Object>> apply(ServerPlayer p,UUID agent,JsonNode args,BooleanSupplier permit){return control(p,agent,args,"APPLY",permit);}
    public static CompletableFuture<Map<String,Object>> control(ServerPlayer p,UUID agent,JsonNode args,String action,BooleanSupplier permit){
        authorize(p,agent,true);require(permit.getAsBoolean(),"CANCELLED");var h=handle(p,agent,id(args));retireCompleted(h);var server=p.level().getServer();
        if(action.equals("pause")){require(h.job!=null,"NOT_RUNNING");h.job.pause();return CompletableFuture.completedFuture(Map.of("id",h.key.id,"status","PAUSE_REQUESTED","verification","UNVERIFIED"));}
        require(h.job==null&&!h.busy,"BUSY");h.busy=true;var result=new CompletableFuture<Map<String,Object>>();
        h.opened.thenCompose(ledger->io(()->{
            var before=ledger.head();
            try {
                require(before.revision()==revision(args),"STALE_REVISION");
                // Read/validate the immutable definition before starting or resuming a durable operation.
                var design=ledger.design(before.revision());var head=before;
                if(action.equals("resume"))head=ledger.resume();
                else if(action.equals("recover"))require(Set.of("UNKNOWN","PARTIAL").contains(head.status()),"NOT_RECOVERABLE");
                else head=ledger.start(head.revision(),action.toUpperCase(Locale.ROOT));
                return new Object[]{ledger,head,design,null};
            }catch(IllegalArgumentException|IllegalStateException rejected){
                // An async exception is not evidence of an uncertain world write. Only return a
                // correction receipt after confirming that this request did not change the ledger.
                if(!before.equals(ledger.head()))throw rejected;
                return new Object[]{ledger,before,null,controlRejection(h.key.id,before,rejected)};
            }
        })).whenComplete((bundle,error)->server.execute(()->{
            h.busy=false;if(error!=null){result.completeExceptionally(error);return;}if(bundle[3]!=null){result.complete((Map<String,Object>)bundle[3]);return;}if(h.closed){result.completeExceptionally(new IllegalStateException("BUILDING_SERVER_STOPPED"));return;}
            var design=(BuildingDesign)bundle[2];if(!design.dimension().equals(p.level().dimension().identifier().toString())){result.completeExceptionally(new IllegalStateException("BUILDING_DIMENSION"));return;}
            h.job=new BuildJob(p,agent,h,(ConstructionLedger)bundle[0],(ConstructionLedger.Head)bundle[1],action.equals("recover"),permit,result);
        }));return result;
    }
    private static Map<String,Object> controlRejection(String id,ConstructionLedger.Head head,RuntimeException rejected){
        String guidance=switch(head.status()){
            case "PLANNED"->"Apply this revision once. Recovery is not needed for a plan that has not run.";
            case "UNVERIFIED","VERIFIED"->"Do not apply again. Call verify_building for this id/revision; if it fails, inspect and plan a corrected local revision.";
            case "PAUSED"->"Resume the existing revision; do not apply it again.";
            case "UNKNOWN","PARTIAL"->"Inspect and recover uncertain cells before deciding whether to undo; do not replay apply.";
            case "PREPARING","APPLYING"->"The operation is already running. Inspect its status instead of starting another apply.";
            case "UNDONE"->"Use redo to restore this revision, or inspect before planning another revision.";
            default->"Inspect this building and its history, then choose an action valid for its current state.";
        };
        var out=new LinkedHashMap<String,Object>();out.put("id",id);out.put("status","REJECTED");out.put("error",Objects.toString(rejected.getMessage(),"BUILDING_CONTROL_REJECTED"));out.put("revision",head.revision());out.put("activeRevision",head.activeRevision());out.put("persistentStatus",head.status());out.put("existingOperation",head.operation());out.put("worldModified",false);out.put("ledgerModified",false);out.put("retryGuidance",guidance);return out;
    }
    private static final class BuildJob implements Work {
        final ServerPlayer player;final UUID agent;final Handle handle;final ConstructionLedger ledger;final ConstructionLedger.Head initial;final ServerLevel level;final long permission;final BooleanSupplier permit;final CompletableFuture<Map<String,Object>> result;
        final Map<String,BlockState> states=new HashMap<>();String phase;long cursor,settle;boolean paused,done,reserved;CompletableFuture<List<ConstructionLedger.Cell>> read;List<ConstructionLedger.Cell> batch;
        BuildJob(ServerPlayer p,UUID agent,Handle handle,ConstructionLedger ledger,ConstructionLedger.Head head,boolean recover,BooleanSupplier permit,CompletableFuture<Map<String,Object>> result){
            this.player=p;this.agent=agent;this.handle=handle;this.ledger=ledger;initial=head;level=p.level();permission=MineAgentRuntimeServices.permissions(level.getServer()).actionRevision(p.getUUID(),PermissionAction.RUN_CODE);this.permit=permit;this.result=result;phase=recover?"RECOVER":head.phase();cursor=recover?0:head.cursor();read=io(()->ledger.page(initial.revision(),cursor,false));
        }
        public void pause(){paused=true;}
        public boolean completed(){return done&&result.isDone();}
        public CompletableFuture<?> pending(){return done?result:read;}
        private boolean current(){return permit.getAsBoolean()&&level.getServer().getPlayerList().getPlayer(player.getUUID())==player&&player.level()==level&&player.isAlive()&&ServerTaskStart.allowed(player,agent)&&player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)&&MineAgentRuntimeServices.permissions(level.getServer()).actionRevision(player.getUUID(),PermissionAction.RUN_CODE)==permission;}
        private BlockState parse(String value)throws Exception {var old=states.get(value);if(old!=null)return old;var parsed=ConversationWorldGeometry.parse(player,value,false);states.put(value,parsed);return parsed;}
        private String actual(BlockPos pos){ConversationWorldGeometry.location(level,pos);require(level.getBlockEntity(pos)==null,"EXISTING_BLOCK_ENTITY");return BlockStateParser.serialize(level.getBlockState(pos));}
        private boolean undo(){return Set.of("UNDO","ROLLBACK").contains(initial.mode());}
        private boolean selected(ConstructionLedger.Cell c){return c.changed()&&(!initial.mode().equals("ROLLBACK")||c.written());}
        private String expected(ConstructionLedger.Cell c){return undo()?c.after():c.before();}
        private String target(ConstructionLedger.Cell c){return undo()?c.before():c.after();}
        private boolean end(String status,String detail){if(done)return result.isDone();done=true;io(()->{ledger.fail(status,detail);return view(handle,ledger,0);}).whenComplete((v,e)->{if(e==null)result.complete(v);else result.completeExceptionally(e);});return result.isDone();}
        public boolean tick(){
            if(done)return result.isDone();if(!read.isDone()||level.getGameTime()<settle)return false;
            try {
                batch=read.join();
                if(paused||!current()){
                    done=true;String resumePhase=reserved?"WRITE":phase;long at=cursor,tick=level.getGameTime();
                    io(()->{ledger.progress("PAUSED",resumePhase,at,tick);return view(handle,ledger,0);}).whenComplete((v,e)->{if(e==null)result.complete(v);else result.completeExceptionally(e);});return result.isDone();
                }
                if(batch.isEmpty()){
                    if(phase.equals("RECOVER"))return end("PARTIAL","Observed uncertain cells; use explicit undo to roll back matching changes before a new revision.");
                    if(phase.equals("VERIFY")){done=true;long tick=level.getGameTime();io(()->{ledger.finish(tick);return view(handle,ledger,0);}).whenComplete((v,e)->{if(e==null)result.complete(v);else result.completeExceptionally(e);});return result.isDone();}
                    phase=phase.equals("PREFLIGHT")?"WRITE":phase.equals("WRITE")?"NOTIFY":"VERIFY";cursor=0;String next=phase;
                    if(phase.equals("VERIFY"))settle=level.getGameTime()+4;
                    read=io(()->{ledger.progress("APPLYING",next,0,0);return ledger.page(initial.revision(),0,false);});return false;
                }
                if(phase.equals("WRITE")&&!reserved){reserved=true;var held=batch;long at=cursor;read=io(()->{ledger.reserve(at);return held;});return false;}
                var samples=new ArrayList<ConstructionLedger.Sample>();var written=new ArrayList<Long>();
                for(var cell:batch){var pos=new BlockPos(cell.x(),cell.y(),cell.z());
                    if(phase.equals("PREFLIGHT")){
                        String actual=actual(pos);
                        if(initial.mode().equals("APPLY")){
                            require(cell.expected()==null||actual.equals(cell.expected()),"WORLD_CONFLICT_AT_"+cell.x()+"_"+cell.y()+"_"+cell.z());
                            String after=BlockStateParser.serialize(ConversationWorldGeometry.oriented(parse(cell.material()),cell.orientation()));
                            if(!actual.equals(after))ConversationWorldGeometry.writable(level,pos,parse(after));
                            samples.add(new ConstructionLedger.Sample(cell.sequence(),actual,after,cell.baseline()==null?actual:cell.baseline()));
                        }else if(selected(cell)){require(actual.equals(expected(cell)),"HISTORY_CONFLICT");ConversationWorldGeometry.writable(level,pos,parse(target(cell)));}
                    }else if(phase.equals("RECOVER")){
                        if(cell.before()==null)continue;String actual=actual(pos);require(actual.equals(cell.before())||actual.equals(cell.after()),"RECOVERY_CONFLICT");if(cell.changed()&&actual.equals(cell.after()))written.add(cell.sequence());
                    }else if(selected(cell)){
                        if(phase.equals("WRITE")){
                            require(actual(pos).equals(expected(cell)),"HISTORY_CONFLICT");var after=parse(target(cell));ConversationWorldGeometry.writable(level,pos,after);
                            level.setBlock(pos,after,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
                            String actual=actual(pos);if(!actual.equals(expected(cell)))written.add(cell.sequence());require(actual.equals(target(cell)),"WRITE_READBACK_MISMATCH");
                        }else if(phase.equals("NOTIFY")){var state=level.getBlockState(pos);state.updateNeighbourShapes(level,pos,Block.UPDATE_ALL);level.updateNeighborsAt(pos,state.getBlock());}
                        else require(actual(pos).equals(target(cell)),"POST_PHYSICS_MISMATCH");
                    }
                }
                cursor=batch.getLast().sequence();long next=cursor,tick=level.getGameTime();String completedPhase=phase;reserved=false;
                read=io(()->{
                    if(!samples.isEmpty())ledger.samples(samples);
                    if(completedPhase.equals("WRITE"))ledger.applied(written,next,tick);
                    else {if(completedPhase.equals("RECOVER"))ledger.reconcile(written);ledger.progress(completedPhase.equals("PREFLIGHT")?"PREPARING":"APPLYING",completedPhase,next,0);}
                    return ledger.page(initial.revision(),next,false);
                });return false;
            }catch(Exception failure){String detail=Objects.toString(failure.getMessage(),failure.getClass().getSimpleName());return end(reserved?"UNKNOWN":phase.equals("PREFLIGHT")?"CONFLICT":"PARTIAL",detail);}
        }
    }
    public static CompletableFuture<Map<String,Object>> verify(ServerPlayer p,UUID agent,JsonNode args,BooleanSupplier permit){
        authorize(p,agent,false);var h=handle(p,agent,id(args));retireCompleted(h);require(!h.busy&&h.job==null,"BUSY");h.busy=true;var result=new CompletableFuture<Map<String,Object>>();var server=p.level().getServer();
        h.opened.thenCompose(ledger->io(()->{var head=ledger.head();require(head.revision()==revision(args)&&head.activeRevision()==head.revision(),"VERIFICATION_REVISION");require(Set.of("UNVERIFIED","VERIFIED").contains(head.status()),"NOT_APPLIED");ledger.verified(head.operation(),head.revision(),false,"");return new Object[]{ledger,head,ledger.design(head.revision()),ledger.bounds(head.revision(),null),ledger.footprintHash(head.revision()),ledger.count(head.revision())};})).whenComplete((data,error)->server.execute(()->{
            h.busy=false;if(error!=null){result.completeExceptionally(error);return;}if(h.closed){result.completeExceptionally(new IllegalStateException("BUILDING_SERVER_STOPPED"));return;}
            h.job=new BuildingVerifier(p,agent,h.key.id,(ConstructionLedger)data[0],(ConstructionLedger.Head)data[1],(BuildingDesign)data[2],(int[])data[3],(String)data[4],(Long)data[5],permit,result);
        }));return result;
    }
    static Map<String,Object> finishedView(String id,ConstructionLedger ledger)throws Exception {var h=ledger.head();return Map.of("id",id,"revision",h.revision(),"status",h.status(),"verification",h.status().equals("VERIFIED")?"VERIFIED":"UNVERIFIED","operation",h.operation(),"report",h.report());}
    interface VerificationWork extends Work {}
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){var handles=LIVE.get(event.getServer());if(handles!=null)for(var handle:List.copyOf(handles.values())){var work=handle.job;if(work!=null&&work.tick()&&handle.job==work)handle.job=null;}}
    @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppedEvent event){var handles=LIVE.remove(event.getServer());if(handles==null)return;for(var handle:handles.values()){handle.closed=true;var pending=handle.job==null?CompletableFuture.completedFuture(null):handle.job.pending();pending.handle((v,e)->null).thenCompose(v->handle.opened).thenCompose(ledger->io(()->{ledger.close();return null;}));}}
    private ServerBuildings(){}
}
