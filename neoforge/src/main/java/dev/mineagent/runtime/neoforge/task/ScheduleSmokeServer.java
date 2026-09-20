package dev.mineagent.runtime.neoforge.task;
import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.api.task.*;
import dev.mineagent.runtime.core.task.*;
import dev.mineagent.runtime.core.scheduling.*;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Actual wall/tick clocks and recorded Native logout conditions across two JVMs; no OS input. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class ScheduleSmokeServer {
    public static volatile boolean ready,finished,questionReady,questionCaptured;public static volatile String failure;
    private static final ObjectMapper JSON=new ObjectMapper();private static final Map<String,UUID> ids=new LinkedHashMap<>();private static UUID agent,task,creatorTask,batch,wakeTask;private static Set<PermissionAction> previous;
    private static int phase,ticks,waitAt;private static long anchor;
    public static boolean enabled(){return Boolean.getBoolean("mineagent.scheduleSmoke");}public static String stage(){return System.getProperty("mineagent.scheduleStage","prepare");}
    private static Path root(MinecraftServer s)throws Exception{var p=s.getServerDirectory().resolve("schedule-evidence");Files.createDirectories(p);return p;}
    private static void save(MinecraftServer s,String name,Object value)throws Exception{Files.writeString(root(s).resolve(stage()+"-"+name+".json"),JSON.writeValueAsString(value));}
    private static void require(boolean ok,String code){if(!ok)throw new IllegalStateException(code);}
    private static ManagedTask task(MinecraftServer s){return MineAgentRuntimeServices.tasks(s).get(task).orElseThrow();}
    private static int calls(MinecraftServer s)throws Exception{return JSON.readTree(Files.readString(s.getServerDirectory().resolve("schedule-provider-count.json"))).path("calls").asInt();}
    private static UUID createTask(MinecraftServer s,UUID owner,String name)throws Exception{var tasks=MineAgentRuntimeServices.tasks(s);var t=tasks.create(agent,owner,name,20,List.of(new TaskStepSpec("fixture_plan",Set.of()),new TaskStepSpec("execute",Set.of("fixture_plan"))));return tasks.completeStep(t.taskId(),t.revision(),"fixture_plan").task().taskId();}
    private static void grant(MinecraftServer s,UUID owner,Set<PermissionAction> values)throws Exception{var config=MineAgentRuntimeServices.config(s);String text=values.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(","));require(config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),Map.of("runtime.initialized","true","voice.output.enabled","false","permission.player."+owner,text)),true).accepted(),"SCHEDULE_FIXTURE_PERMISSIONS");MineAgentRuntimeServices.permissions(s).setTrustedActions(owner,values);}
    private static void submit(MinecraftServer s,String tool,Map<String,Object> arguments)throws Exception{batch=MineAgentRuntimeServices.taskExecutor(s).worldActions().submit(task(s),List.of(Map.of("id","schedule-fixture-"+UUID.randomUUID(),"name",tool,"arguments",JSON.writeValueAsString(arguments)))).batchId();}
    private static Map<String,Object> periodic(String policy){return Map.of("kind","WALL_PERIODIC","mode","RECORD_ONLY","at",Instant.ofEpochMilli(anchor).toString(),"period_ms",1000,"max_occurrences",32,"ttl_seconds",120,"timezone","Asia/Shanghai","missed_policy",policy);}
    private static Map<String,Object> condition(boolean initial){return Map.of("kind","EVENT_CONDITION","mode","RECORD_ONLY","subscription_id",ids.get("subscription"),"subscription_revision",1,"fire_if_initially_matched",initial,"ttl_seconds",600);}
    private static PersistentScheduleStore.Definition definition(MinecraftServer s,String key)throws Exception{var r=MineAgentRuntimeServices.schedules(s);return r.store().inspect(r.context(task(s),UUID.randomUUID()),ids.get(key));}
    private static List<PersistentScheduleStore.Occurrence> occurrences(MinecraftServer s,String key)throws Exception{var r=MineAgentRuntimeServices.schedules(s);return r.store().occurrences(r.context(task(s),UUID.randomUUID()),ids.get(key));}
    private static void journal(MinecraftServer s)throws Exception{Files.writeString(root(s).resolve("journal.json"),JSON.writeValueAsString(Map.of("agent",agent,"creatorTask",creatorTask,"ids",ids,"previous",previous,"anchor",anchor)));}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!enabled()||finished||failure!=null)return;var server=event.getServer();var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;ticks++;
        try{require(ticks<3600,"SCHEDULE_NATIVE_TIMEOUT");var runtime=MineAgentRuntimeServices.schedules(server);var store=runtime.store();var actions=MineAgentRuntimeServices.taskExecutor(server).worldActions();
            if(phase==0){if(stage().equals("prepare")){
                    previous=MineAgentRuntimeServices.permissions(server).trustedActions(viewer.getUUID());var allowed=new HashSet<>(previous);allowed.addAll(Set.of(PermissionAction.SCHEDULE_TASKS,PermissionAction.DISCOVER_OBJECTS,PermissionAction.SUBSCRIBE_EVENTS));grant(server,viewer.getUUID(),allowed);
                    agent=MineAgentRuntimeServices.bodies(server).createPersistentAt("持久调度 Actor",viewer.getUUID(),server.overworld(),viewer.position().add(2,0,0)).agentId();task=createTask(server,viewer.getUUID(),"持久UTC/tick调度固定配置验收");creatorTask=task;anchor=System.currentTimeMillis()+1000;ready=true;submit(server,"inspect_clock",Map.of());phase=1;
                }else{require(stage().equals("resume"),"SCHEDULE_STAGE");var n=JSON.readTree(Files.readString(root(server).resolve("journal.json")));agent=UUID.fromString(n.path("agent").asText());creatorTask=UUID.fromString(n.path("creatorTask").asText());n.path("ids").fields().forEachRemaining(e->ids.put(e.getKey(),UUID.fromString(e.getValue().asText())));var restored=new HashSet<PermissionAction>();n.path("previous").forEach(v->restored.add(PermissionAction.valueOf(v.asText())));previous=Set.copyOf(restored);anchor=n.path("anchor").asLong();if(MineAgentRuntimeServices.bodies(server).body(agent).isEmpty())return;
                    task=creatorTask;require(occurrences(server,"wall").size()==1&&occurrences(server,"tick").size()==1&&occurrences(server,"cancel").isEmpty(),"SCHEDULE_RESTART_REPLAYED_ONCE");require(definition(server,"expired").state().equals("EXPIRED"),"SCHEDULE_EXPIRED_RESTORED_WRONGLY");
                    var once=definition(server,"wall");require(store.create(runtime.context(task(server),once.creator().operation()),once.spec()).id().equals(once.id()),"SCHEDULE_CREATE_REPLAY_CHANGED");
                    task=createTask(server,viewer.getUUID(),"调度重启及取消回归");ready=true;phase=20;
                }return;}
            if(ticks%20==0)save(server,"progress",Map.of("phase",phase,"ticks",ticks,"calls",calls(server),"utc",Instant.now().toString(),"gameTicks",server.overworld().getGameTime()));
            if(phase>=1&&phase<=11||phase>=21&&phase<=25||phase==28||phase==29){var b=actions.list(viewer.getUUID()).stream().filter(v->v.batchId().equals(batch)).findFirst().orElseThrow();if(!b.state().equals("COMPLETED")){require(!Set.of("FAILED","INTERRUPTED").contains(b.state()),"SCHEDULE_ACTION_FAILED_"+phase+"_"+b.error());return;}save(server,"phase-"+phase,b);var value=JSON.readTree(b.receipts().getLast().after().get("result"));
                switch(phase){
                    case 1->{require(value.has("epochMillis")&&value.has("gameTicks"),"SCHEDULE_CLOCK_MISSING");submit(server,"create_schedule",Map.of("kind","WALL_ONCE","mode","RECORD_ONLY","delay_ms",500,"ttl_seconds",600,"timezone","Asia/Shanghai"));phase=2;}
                    case 2->{ids.put("wall",UUID.fromString(value.path("id").asText()));submit(server,"create_schedule",Map.of("kind","TICK_ONCE","mode","RECORD_ONLY","delay_ticks",20,"ttl_seconds",600));phase=3;}
                    case 3->{ids.put("tick",UUID.fromString(value.path("id").asText()));submit(server,"create_schedule",periodic("SKIP"));phase=4;}
                    case 4->{ids.put("skip",UUID.fromString(value.path("id").asText()));submit(server,"create_schedule",periodic("COALESCE"));phase=5;}
                    case 5->{ids.put("coalesce",UUID.fromString(value.path("id").asText()));submit(server,"create_schedule",Map.of("kind","WALL_ONCE","mode","RECORD_ONLY","at",Instant.ofEpochMilli(System.currentTimeMillis()-5000).toString(),"ttl_seconds",1,"timezone","UTC"));phase=6;}
                    case 6->{ids.put("expired",UUID.fromString(value.path("id").asText()));submit(server,"create_schedule",Map.of("kind","WALL_ONCE","mode","AGENT_WAKE","goal","SCHEDULE_CANCEL_SHOULD_NOT_REACH_PROVIDER","max_model_calls",1,"delay_ms",60000,"ttl_seconds",60));phase=7;}
                    case 7->{ids.put("cancel",UUID.fromString(value.path("id").asText()));submit(server,"set_schedule_state",Map.of("schedule_id",ids.get("cancel"),"expected_revision",1,"state","CANCELLED"));phase=8;}
                    case 8->{submit(server,"subscribe_events",Map.of("sources",List.of("PLAYER_LEAVE"),"mode","RECORD_ONLY","query",Map.of("kind","PLAYER","ids",List.of(viewer.getUUID().toString())),"ttl_seconds",600));phase=9;}
                    case 9->{ids.put("subscription",UUID.fromString(value.path("id").asText()));submit(server,"create_schedule",condition(false));phase=10;}
                    case 10->{ids.put("condition-future",UUID.fromString(value.path("id").asText()));submit(server,"create_schedule",Map.of("kind","WALL_ONCE","mode","AGENT_WAKE","goal","SCHEDULE_WAKE_NATIVE_GOAL: 到期后提出一个普通说明问题，不修改世界。","max_model_calls",1,"delay_ms",60000,"ttl_seconds",120,"timezone","Asia/Shanghai"));phase=11;}
                    case 11->{ids.put("wake",UUID.fromString(value.path("id").asText()));phase=12;}
                    case 21->{ids.put("condition-initial",UUID.fromString(value.path("id").asText()));submit(server,"create_schedule",condition(false));phase=22;}
                    case 22->{ids.put("condition-next",UUID.fromString(value.path("id").asText()));submit(server,"set_schedule_state",Map.of("schedule_id",ids.get("skip"),"expected_revision",1,"state","PAUSED"));phase=23;}
                    case 23->{submit(server,"set_schedule_state",Map.of("schedule_id",ids.get("skip"),"expected_revision",2,"state","ACTIVE"));phase=24;}
                    case 24->{waitAt=ticks;phase=25;}
                    case 25->{if(ticks-waitAt<40)return;require(occurrences(server,"condition-initial").size()==1&&occurrences(server,"condition-next").isEmpty(),"SCHEDULE_INITIAL_CONDITION_POLICY");submit(server,"set_schedule_state",Map.of("schedule_id",ids.get("condition-next"),"expected_revision",1,"state","CANCELLED"));phase=26;}
                    case 28->{submit(server,"set_schedule_state",Map.of("schedule_id",ids.get("wake"),"expected_revision",2,"state","ACTIVE"));phase=29;}
                    case 29->{waitAt=ticks;phase=30;}
                    default->throw new IllegalStateException("SCHEDULE_PHASE");
                }return;
            }
            if(phase==12){if(occurrences(server,"wall").size()!=1||occurrences(server,"tick").size()!=1||occurrences(server,"skip").isEmpty()||occurrences(server,"coalesce").isEmpty())return;
                require(occurrences(server,"expired").isEmpty()&&occurrences(server,"cancel").isEmpty()&&occurrences(server,"condition-future").isEmpty()&&calls(server)==0,"SCHEDULE_PREPARE_EARLY_WORK");journal(server);save(server,"result",Map.of("status","SCHEDULE_PREPARE_VERIFIED","wall",definition(server,"wall"),"tick",definition(server,"tick"),"skip",definition(server,"skip"),"coalesce",definition(server,"coalesce"),"wake",definition(server,"wake"),"calls",0,"systemInputInjected",false));finished=true;return;}
            if(phase==20){var condition=occurrences(server,"condition-future");if(condition.isEmpty())return;require(condition.size()==1&&condition.getFirst().conditionEvent()!=null,"SCHEDULE_NATIVE_EVENT_CONDITION");require(definition(server,"skip").skipped()>0&&definition(server,"coalesce").skipped()>0,"SCHEDULE_RESTART_MISSED_POLICY");save(server,"restart",Map.of("wall",occurrences(server,"wall"),"tick",occurrences(server,"tick"),"skip",definition(server,"skip"),"coalesce",definition(server,"coalesce"),"condition",condition));submit(server,"create_schedule",condition(true));phase=21;return;}
            if(phase==26){var b=actions.list(viewer.getUUID()).stream().filter(v->v.batchId().equals(batch)).findFirst().orElseThrow();if(!b.state().equals("COMPLETED"))return;var values=occurrences(server,"wake");if(values.isEmpty())return;var o=values.getFirst();require(values.size()==1&&o.recordedAt()>=definition(server,"wake").start(),"SCHEDULE_EARLY_OR_DUPLICATE_WAKE");if(!o.state().equals("DISPATCHED"))return;wakeTask=o.taskId();var child=MineAgentRuntimeServices.tasks(server).get(wakeTask).orElseThrow();if(child.steps().stream().noneMatch(s->s.status()==TaskNodeStatus.WAITING_FOR_PLAYER))return;require(calls(server)==1&&o.modelAttempts()==1,"SCHEDULE_PROVIDER_CALL_COUNT");save(server,"wake",Map.of("occurrence",o,"task",child,"calls",calls(server)));questionReady=true;phase=27;return;}
            if(phase==27&&questionCaptured){submit(server,"set_schedule_state",Map.of("schedule_id",ids.get("wake"),"expected_revision",1,"state","PAUSED"));phase=28;return;}
            if(phase==30&&ticks-waitAt>=60){var child=MineAgentRuntimeServices.tasks(server).get(wakeTask).orElseThrow();require(TaskAuthorityFence.revoked(child)&&calls(server)==1&&occurrences(server,"wake").size()==1&&occurrences(server,"wake").getFirst().state().equals("CANCELLED"),"SCHEDULE_OLD_OCCURRENCE_REVIVED");
                var old=runtime.context(task(server),UUID.randomUUID());var allowed=new HashSet<>(MineAgentRuntimeServices.permissions(server).trustedActions(viewer.getUUID()));var denied=new HashSet<>(allowed);denied.remove(PermissionAction.SCHEDULE_TASKS);grant(server,viewer.getUUID(),denied);grant(server,viewer.getUUID(),allowed);boolean rejected=false;try{store.inspect(old,ids.get("wall"));}catch(SecurityException expected){rejected=true;}require(rejected,"SCHEDULE_OLD_GRANT_REVIVED");
                var checks=new ArrayList<Map<String,Object>>();for(String key:List.of("wall","tick","condition-future","condition-initial"))checks.add(Map.of("kind","schedule_occurrence","schedule_id",ids.get(key),"index",0,"state","RECORDED"));checks.add(Map.of("kind","schedule_occurrence","schedule_id",ids.get("wake"),"index",0,"state","CANCELLED"));actions.finishTask(task(server),JSON.writeValueAsString(Map.of("checks",checks)));save(server,"result",Map.of("status","NATIVE_SCHEDULE_CLOCKS_CONDITIONS_AND_FENCES_VERIFIED","task",task(server),"wake",child,"calls",calls(server),"oldGrantRejected",true,"systemInputInjected",false,"realModel",false,"deliveryVerified",false));journal(server);grant(server,viewer.getUUID(),previous);finished=true;
            }
        }catch(Exception e){failure=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();save(server,"failure",Map.of("phase",phase,"error",e.toString()));}
    }
}
