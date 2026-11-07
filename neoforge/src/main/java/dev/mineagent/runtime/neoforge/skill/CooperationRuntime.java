package dev.mineagent.runtime.neoforge.skill;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.core.persistence.SqliteRuntimeRepository;
import dev.mineagent.runtime.core.task.*;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.ui.ConversationAgentScope;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Durable dependency scheduling over the existing skills, including repeated material handoffs. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class CooperationRuntime {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String SPACE="cooperation_v2";
    private static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
    private static final Map<MinecraftServer,CooperationRuntime> ALL=new IdentityHashMap<>();
    public record Step(String node,UUID operation,int round,String state,String reason,SkillSession.Snapshot observed){}
    public record Run(UUID id,UUID owner,UUID leader,UUID world,CooperationPlan plan,long revision,String state,String reason,List<Step> steps,List<String> events){}
    private static final class Entry {
        Run value;long dbRevision;CompletableFuture<Void> saved=CompletableFuture.completedFuture(null);boolean checking,ioFailed;
        final Map<UUID,Long> epochs=new HashMap<>();
        Entry(Run value,long revision){this.value=value;dbRevision=revision;}
    }
    private final MinecraftServer server;private final UUID world;private final Map<UUID,Entry> runs=new LinkedHashMap<>();private final CompletableFuture<Void> loaded=new CompletableFuture<>();
    private CooperationRuntime(MinecraftServer server){
        this.server=server;world=MineAgentRuntimeServices.worldId(server);
        CompletableFuture.supplyAsync(()->{try(var db=new SqliteRuntimeRepository(database())){return db.list(world,SPACE);}catch(Exception error){throw new CompletionException(error);}},IO).whenComplete((rows,error)->server.execute(()->{
            if(error!=null){loaded.completeExceptionally(error);return;}try{for(var row:rows){Run value=JSON.readValue(row.payload(),Run.class);if(!value.world().equals(world))throw new SecurityException("COOP_WORLD_CHANGED");var entry=new Entry(value,row.revision());runs.put(value.id(),entry);if(value.state().equals("RUNNING"))change(entry,"PAUSED","RESTART_RECHECK",value.steps(),"重启后保留进度，等待重新核对并恢复");}loaded.complete(null);}catch(Exception failed){loaded.completeExceptionally(failed);}
        }));
    }
    public static CooperationRuntime get(MinecraftServer server){return ALL.computeIfAbsent(server,CooperationRuntime::new);}
    private java.nio.file.Path database(){return server.getServerDirectory().resolve("mineagent-runtime-data/runtime.db");}
    private ServerPlayer owner(Entry e){return server.getPlayerList().getPlayer(e.value.owner());}
    private void authorize(ServerPlayer owner,UUID leader,CooperationPlan plan){
        if(!server.isSameThread()||server.getPlayerList().getPlayer(owner.getUUID())!=owner||!ConversationAgentScope.allowed(owner,leader,leader))throw new SecurityException("COOP_OWNER_PERMISSION");
        for(UUID member:plan.agents())if(!ConversationAgentScope.allowed(owner,leader,member))throw new SecurityException("COOP_SAME_OWNER_REQUIRED "+member);
    }
    private boolean current(Entry entry){
        if(entry.ioFailed||!entry.value.state().equals("RUNNING"))return false;var owner=owner(entry);if(owner==null)return false;
        try{authorize(owner,entry.value.leader(),entry.value.plan());for(var epoch:entry.epochs.entrySet())if(BehaviorAuthority.get(server).revision(owner,epoch.getKey())!=epoch.getValue())return false;return true;}catch(Exception denied){return false;}
    }
    private void epochs(Entry e,ServerPlayer p){e.epochs.clear();var members=new HashSet<>(e.value.plan().agents());members.add(e.value.leader());for(var member:members)e.epochs.put(member,BehaviorAuthority.get(server).revision(p,member));}
    private void save(Entry e){String payload;try{payload=JSON.writeValueAsString(e.value);}catch(Exception failed){throw new IllegalStateException("COOP_ENCODING",failed);}UUID id=e.value.id();e.saved=e.saved.thenRunAsync(()->{try(var db=new SqliteRuntimeRepository(database())){if(!db.compareAndSet(world,SPACE,id.toString(),e.dbRevision,payload,System.currentTimeMillis()).accepted())throw new IllegalStateException("COOP_STORAGE_CONFLICT");e.dbRevision++;}catch(Exception error){e.ioFailed=true;throw new CompletionException(error);}},IO);}
    private void change(Entry e,String state,String reason,List<Step> steps,String event){
        var v=e.value;var events=new ArrayList<>(v.events());if(event!=null&&!event.isBlank()){events.add(event);while(events.size()>128)events.removeFirst();}
        e.value=new Run(v.id(),v.owner(),v.leader(),v.world(),v.plan(),v.revision()+1,state,reason,List.copyOf(steps),List.copyOf(events));save(e);
    }
    private Step step(Entry e,String id){return e.value.steps().stream().filter(s->s.node().equals(id)).findFirst().orElseThrow();}
    private void step(Entry e,Step next){var rows=new ArrayList<>(e.value.steps());rows.replaceAll(old->old.node().equals(next.node())?next:old);change(e,e.value.state(),e.value.reason(),rows,"");}
    private Map<String,Object> view(Entry e){return Map.of("status",e.value.state(),"id",e.value.id(),"revision",e.value.revision(),"plan",e.value.plan(),"steps",e.value.steps(),"events",e.value.events(),"reason",e.value.reason(),"pendingReceipt",!e.saved.isDone(),"goalComplete",e.value.state().equals("COMPLETED"));}
    public CompletableFuture<Map<String,Object>> start(ServerPlayer owner,UUID leader,UUID operation,JsonNode args,BooleanSupplier permit){
        var plan=CooperationPlan.parse(args.path("plan"));authorize(owner,leader,plan);
        return loaded.thenComposeAsync(ignored->{authorize(owner,leader,plan);if(!permit.getAsBoolean())throw new IllegalStateException("COOP_REQUEST_CHANGED");var old=runs.get(operation);if(old!=null){if(!old.value.owner().equals(owner.getUUID())||!old.value.leader().equals(leader)||!old.value.plan().equals(plan))throw new IllegalArgumentException("COOP_OPERATION_REUSED");return CompletableFuture.completedFuture(view(old));}
            var skills=SkillRuntime.get(server);for(var member:plan.agents())if(!skills.cooperativeIdle(member))throw new IllegalStateException("COOP_MEMBER_BUSY "+member);
            for(var existing:runs.values())if(existing.value.state().equals("RUNNING")&&!Collections.disjoint(existing.value.plan().agents(),plan.agents()))throw new IllegalStateException("COOP_MEMBER_RESERVED");
            var bodies=MineAgentRuntimeServices.bodies(server);for(var supply:plan.supplies()){var build=plan.node(supply.construction());var recipient=bodies.body(build.agent()).orElseThrow(()->new IllegalStateException("COOP_RECIPIENT_BODY_UNAVAILABLE"));if(!plan.node(supply.handoff()).spec().target().equals(recipient.getUUID().toString()))throw new IllegalArgumentException("supplies."+supply.handoff()+": handoff target must be the construction actor's actual body UUID");}
            var steps=plan.nodes().stream().map(n->new Step(n.id(),UUID.randomUUID(),1,"PENDING","",null)).toList();var run=new Run(operation,owner.getUUID(),leader,world,plan,1,"RUNNING","",steps,List.of("已保存协作依赖，执行结果以子任务回执为准"));var entry=new Entry(run,0);epochs(entry,owner);runs.put(operation,entry);save(entry);
            return entry.saved.thenApplyAsync(v->{if(!permit.getAsBoolean()){change(entry,"PAUSED","START_CONTEXT_CHANGED",entry.value.steps(),"");}var result=new LinkedHashMap<>(view(entry));result.put("status",entry.value.state().equals("RUNNING")?"STARTED":entry.value.state());return (Map<String,Object>)result;},server::execute);
        },server::execute);
    }
    public CompletableFuture<Map<String,Object>> inspect(ServerPlayer owner,UUID leader,JsonNode args){return loaded.thenApplyAsync(v->{if(!ConversationAgentScope.allowed(owner,leader,leader))throw new SecurityException("COOP_OWNER_PERMISSION");if(args.has("id"))return view(owned(owner,leader,args));int offset=args.path("offset").asInt(0);if(offset<0)throw new IllegalArgumentException("COOP_OFFSET");var values=runs.values().stream().filter(e->e.value.owner().equals(owner.getUUID())&&e.value.leader().equals(leader)).toList();return Map.of("status","OBSERVED","runs",values.stream().skip(offset).limit(16).map(this::view).toList(),"nextOffset",offset+16<values.size()?offset+16:-1);},server::execute);}
    private Entry owned(ServerPlayer p,UUID leader,JsonNode args){var e=runs.get(UUID.fromString(args.path("id").asText()));if(e==null||!e.value.owner().equals(p.getUUID())||!e.value.leader().equals(leader))throw new SecurityException("COOP_NOT_OWNED");authorize(p,leader,e.value.plan());return e;}
    public CompletableFuture<Map<String,Object>> control(ServerPlayer owner,UUID leader,JsonNode args,BooleanSupplier permit){return loaded.thenComposeAsync(v->{var e=owned(owner,leader,args);if(!permit.getAsBoolean()||e.value.revision()!=args.path("expected_revision").asLong())throw new IllegalStateException("COOP_STALE_REVISION");if(Set.of("CANCELLED","COMPLETED").contains(e.value.state()))throw new IllegalStateException("COOP_TERMINAL");String action=args.path("action").asText();if(!Set.of("pause","resume","stop").contains(action))throw new IllegalArgumentException("COOP_ACTION");
        if(action.equals("resume")){var ownOperations=new HashSet<UUID>();for(var step:e.value.steps())ownOperations.add(step.operation());for(UUID member:e.value.plan().agents())if(!SkillRuntime.get(server).cooperativeAvailable(member,ownOperations))throw new IllegalStateException("COOP_MEMBER_HAS_NEW_WORK "+member);for(var other:runs.values())if(other!=e&&other.value.state().equals("RUNNING")&&!Collections.disjoint(other.value.plan().agents(),e.value.plan().agents()))throw new IllegalStateException("COOP_MEMBER_RESERVED");epochs(e,owner);change(e,"RUNNING","",e.value.steps(),"重新核对权限与子任务后恢复");}else change(e,action.equals("stop")?"CANCELLED":"PAUSED",action.equals("stop")?"USER_CANCELLED":"USER_PAUSED",e.value.steps(),action.equals("stop")?"玩家停止了协作":"玩家暂停了协作");
        var actions=new ArrayList<CompletableFuture<?>>();for(var row:e.value.steps())if(Set.of("STARTING","ACTIVE").contains(row.state()))actions.add(SkillRuntime.get(server).controlCooperative(owner,e.value.plan().node(row.node()).agent(),row.operation(),action,()->current(e)));
        return CompletableFuture.allOf(actions.toArray(CompletableFuture[]::new)).handleAsync((ignored,error)->{if(error!=null)change(e,"PAUSED","CHILD_RECONCILIATION_REQUIRED",e.value.steps(),message(error));return view(e);},server::execute);
    },server::execute);}
    private void advance(Entry e){
        if(e.checking||!e.saved.isDone()||e.ioFailed||!e.value.state().equals("RUNNING"))return;
        if(!current(e)){change(e,"PAUSED",owner(e)==null?"OWNER_OFFLINE":"AUTHORITY_OR_PLAYER_INTENT_CHANGED",e.value.steps(),"执行上下文变化，已暂停协作");return;}
        var owner=owner(e);var skills=SkillRuntime.get(server);
        // Missing materials restart only already-verified supply stages, never an uncertain operation.
        for(var supply:e.value.plan().supplies()){
            var build=step(e,supply.construction());var gather=step(e,supply.gather());var handoff=step(e,supply.handoff());
            if(!build.state().equals("ACTIVE")||!gather.state().equals("COMPLETED")||!handoff.state().equals("COMPLETED"))continue;
            String missing=Objects.toString(skills.cooperativeDetails(build.operation()).get("missingItem"),"");if(!missing.equals(e.value.plan().node(gather.node()).spec().crop()))continue;
            var rows=new ArrayList<>(e.value.steps());rows.replaceAll(row->Set.of(gather.node(),handoff.node()).contains(row.node())?new Step(row.node(),UUID.randomUUID(),row.round()+1,"PENDING","MATERIAL_SHORTAGE",null):row);
            change(e,"RUNNING","",rows,"施工缺少 "+missing+"，已重新委派 "+gather.node()+" 和 "+handoff.node());return;
        }
        var active=new ArrayList<Step>();for(var row:e.value.steps())if(Set.of("STARTING","ACTIVE").contains(row.state()))active.add(row);
        var busy=new HashSet<UUID>();for(var row:active)busy.add(e.value.plan().node(row.node()).agent());
        for(var row:e.value.steps())if(row.state().equals("PENDING")){
            var node=e.value.plan().node(row.node());if(busy.contains(node.agent())||node.after().stream().anyMatch(id->!step(e,id).state().equals("COMPLETED")))continue;
            if(!skills.cooperativeIdle(node.agent())){change(e,"PAUSED","MEMBER_HAS_NEW_WORK",e.value.steps(),"成员已有其他工作："+node.agent());return;}
            step(e,new Step(row.node(),row.operation(),row.round(),"STARTING","",null));e.checking=true;
            e.saved.thenComposeAsync(v->{if(!current(e))return CompletableFuture.failedFuture(new IllegalStateException("COOP_CANCELLED_BEFORE_CHILD"));return skills.startCooperative(owner,node.agent(),row.operation(),node.intent(),()->current(e));},server::execute).whenComplete((result,error)->server.execute(()->{
                e.checking=false;if(!e.value.state().equals("RUNNING"))return;if(error!=null){change(e,"PAUSED","CHILD_START_FAILED",e.value.steps(),message(error));return;}step(e,new Step(row.node(),row.operation(),row.round(),"ACTIVE","",null));
            }));return;
        }
        if(active.isEmpty()){if(e.value.steps().stream().allMatch(row->row.state().equals("COMPLETED")))change(e,"COMPLETED","ALL_NATIVE_STEPS_VERIFIED",e.value.steps(),"所有子任务均已取得实际完成回执");return;}
        e.checking=true;long revision=e.value.revision();var reads=new ArrayList<CompletableFuture<SkillSession.Snapshot>>();for(var row:active)reads.add(skills.cooperativeSnapshot(owner,e.value.plan().node(row.node()).agent(),row.operation()));
        CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).whenComplete((v,error)->server.execute(()->{
            e.checking=false;if(!e.value.state().equals("RUNNING")||e.value.revision()!=revision)return;if(error!=null){change(e,"PAUSED","CHILD_READ_FAILED",e.value.steps(),message(error));return;}
            var rows=new ArrayList<>(e.value.steps());String pause="";
            for(int i=0;i<active.size();i++){var old=active.get(i);var snapshot=reads.get(i).join();if(snapshot==null){pause="CHILD_START_OUTCOME_REQUIRES_RECHECK";continue;}String state=snapshot.state()==SkillSession.State.COMPLETED?"COMPLETED":"ACTIVE";
                if(Set.of(SkillSession.State.CANCELLED,SkillSession.State.FAILED,SkillSession.State.PAUSED).contains(snapshot.state()))pause="CHILD_"+snapshot.state()+": "+snapshot.reason();
                var next=new Step(old.node(),old.operation(),old.round(),state,snapshot.reason(),snapshot);rows.replaceAll(row->row.node().equals(old.node())?next:row);
            }
            if(!rows.equals(e.value.steps())||!pause.isEmpty())change(e,pause.isEmpty()?"RUNNING":"PAUSED",pause,rows,pause.isEmpty()?"":pause);
        }));
    }
    public static void stopForAgent(ServerPlayer owner,UUID agent){var runtime=ALL.get(owner.level().getServer());if(runtime==null)return;for(var e:runtime.runs.values())if(e.value.owner().equals(owner.getUUID())&&(e.value.leader().equals(agent)||e.value.plan().agents().contains(agent))&&!Set.of("COMPLETED","CANCELLED").contains(e.value.state())){runtime.change(e,"CANCELLED","PLAYER_STOPPED_MEMBER",e.value.steps(),"玩家停止成员，协作不再派发旧任务");for(var step:e.value.steps())if(Set.of("STARTING","ACTIVE").contains(step.state()))SkillRuntime.get(runtime.server).controlCooperative(owner,e.value.plan().node(step.node()).agent(),step.operation(),"stop",()->false);}}
    private static String message(Throwable error){while(error instanceof CompletionException&&error.getCause()!=null)error=error.getCause();String text=Objects.toString(error.getMessage(),error.getClass().getSimpleName());return text.substring(0,Math.min(text.length(),2048));}
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){var runtime=get(event.getServer());if(!runtime.loaded.isDone()||runtime.loaded.isCompletedExceptionally()||event.getServer().getTickCount()%10!=0)return;for(var entry:List.copyOf(runtime.runs.values()))runtime.advance(entry);}
    @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppingEvent event){var runtime=ALL.remove(event.getServer());if(runtime==null)return;for(var e:runtime.runs.values())if(e.value.state().equals("RUNNING"))runtime.change(e,"PAUSED","SERVER_RESTART",e.value.steps(),"");try{CompletableFuture.allOf(runtime.runs.values().stream().map(e->e.saved).toArray(CompletableFuture[]::new)).get(10,TimeUnit.SECONDS);}catch(Exception ignored){/* Persisted STARTING never authorizes blind replay after restart. */}}
}
