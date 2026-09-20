package dev.mineagent.runtime.neoforge.task;
import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.api.task.*;
import dev.mineagent.runtime.core.task.*;
import dev.mineagent.runtime.core.events.*;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Actual login/logout across two JVMs. Only the provider response is a declared localhost fixture. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class EventSmokeServer {
    public static volatile boolean ready,finished,questionReady,questionCaptured;public static volatile String failure;
    private static final ObjectMapper JSON=new ObjectMapper();private static UUID agent,task,batch,recordSub,wakeSub,cancelSub,wakeTask;private static Set<PermissionAction> previous;
    private static int phase,ticks,waitAt;private static boolean cancelHandled;
    public static boolean enabled(){return Boolean.getBoolean("mineagent.eventSmoke");}
    public static String stage(){return System.getProperty("mineagent.eventStage","prepare");}
    private static Path root(MinecraftServer s)throws Exception{var path=s.getServerDirectory().resolve("events-evidence");Files.createDirectories(path);return path;}
    private static void save(MinecraftServer s,String name,Object value)throws Exception{Files.writeString(root(s).resolve(stage()+"-"+name+".json"),JSON.writeValueAsString(value));}
    private static void require(boolean ok,String error){if(!ok)throw new IllegalStateException(error);}
    private static ManagedTask task(MinecraftServer s){return MineAgentRuntimeServices.tasks(s).get(task).orElseThrow();}
    private static int calls(MinecraftServer s)throws Exception{return JSON.readTree(Files.readString(s.getServerDirectory().resolve("events-provider-count.json"))).path("calls").asInt();}
    private static void grant(MinecraftServer s,UUID owner,Set<PermissionAction> values)throws Exception{var config=MineAgentRuntimeServices.config(s);String text=values.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(","));require(config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),Map.of("runtime.initialized","true","voice.output.enabled","false","permission.player."+owner,text)),true).accepted(),"EVENT_FIXTURE_PERMISSION_CONFIG");MineAgentRuntimeServices.permissions(s).setTrustedActions(owner,values);}
    private static void submit(MinecraftServer s,String tool,Map<String,Object> args)throws Exception{batch=MineAgentRuntimeServices.taskExecutor(s).worldActions().submit(task(s),List.of(Map.of("id","event-fixture-"+UUID.randomUUID(),"name",tool,"arguments",JSON.writeValueAsString(args)))).batchId();}
    private static Map<String,Object> subscription(ServerPlayer viewer,String mode,String goal){var value=new LinkedHashMap<String,Object>();value.put("sources",mode.equals("RECORD_ONLY")?List.of("PLAYER_JOIN","PLAYER_LEAVE"):List.of("PLAYER_JOIN"));value.put("mode",mode);value.put("query",Map.of("kind","PLAYER","ids",List.of(viewer.getUUID().toString())));value.put("cooldown_ms",0);value.put("ttl_seconds",7200);if(mode.equals("AGENT_WAKE")){value.put("goal",goal);value.put("max_wakes",1);value.put("max_model_calls",1);}return value;}
    private static void load(MinecraftServer s)throws Exception{var n=JSON.readTree(Files.readString(root(s).resolve("journal.json")));agent=UUID.fromString(n.path("agent").asText());task=UUID.fromString(n.path("task").asText());recordSub=UUID.fromString(n.path("record").asText());wakeSub=UUID.fromString(n.path("wake").asText());cancelSub=UUID.fromString(n.path("cancel").asText());var values=new HashSet<PermissionAction>();n.path("previous").forEach(v->values.add(PermissionAction.valueOf(v.asText())));previous=Set.copyOf(values);}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void cancelQueuedJoin(PlayerEvent.PlayerLoggedInEvent event)throws Exception{
        if(!enabled()||!stage().equals("resume")||cancelHandled||!(event.getEntity() instanceof ServerPlayer viewer)||viewer instanceof MineAgentPlayer)return;var server=viewer.level().getServer();
        try{load(server);var runtime=MineAgentRuntimeServices.events(server);var c=runtime.context(task(server),UUID.randomUUID());var values=runtime.store().triggers(c,cancelSub);require(values.size()==1&&values.getFirst().state().equals("QUEUED"),"EVENT_CANCEL_NOT_QUEUED");
            var reply=runtime.execute(task(server),UUID.randomUUID(),"set_subscription_state",JSON.writeValueAsString(Map.of("subscription_id",cancelSub,"expected_revision",1,"state","CANCELLED")));require(MineAgentRuntimeServices.tasks(server).get(values.getFirst().taskId()).isEmpty(),"EVENT_CANCEL_TASK_CREATED");save(server,"cancel-before-dispatch",Map.of("trigger",values.getFirst(),"reply",reply,"taskCreated",false));cancelHandled=true;
        }catch(Exception e){failure=e.getMessage();save(server,"join-failure",Map.of("error",e.toString()));}
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!enabled()||finished||failure!=null)return;var server=event.getServer();var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;ticks++;
        try{require(ticks<2200,"EVENT_NATIVE_TIMEOUT");var runtime=MineAgentRuntimeServices.events(server);var store=runtime.store();var actions=MineAgentRuntimeServices.taskExecutor(server).worldActions();
            if(phase==0){if(stage().equals("prepare")){
                    previous=MineAgentRuntimeServices.permissions(server).trustedActions(viewer.getUUID());var allowed=new HashSet<>(previous);allowed.add(PermissionAction.DISCOVER_OBJECTS);allowed.add(PermissionAction.SUBSCRIBE_EVENTS);grant(server,viewer.getUUID(),allowed);
                    agent=MineAgentRuntimeServices.bodies(server).createPersistentAt("事件唤醒 Actor",viewer.getUUID(),server.overworld(),viewer.position().add(2,0,0)).agentId();var manager=MineAgentRuntimeServices.tasks(server);var t=manager.create(agent,viewer.getUUID(),"事件订阅固定配置验收，不轮询模型",20,List.of(new TaskStepSpec("fixture_plan",Set.of()),new TaskStepSpec("execute",Set.of("fixture_plan"))));task=manager.completeStep(t.taskId(),t.revision(),"fixture_plan").task().taskId();ready=true;submit(server,"subscribe_events",subscription(viewer,"RECORD_ONLY",""));phase=1;
                }else{require(stage().equals("resume"),"EVENT_STAGE_INVALID");load(server);ready=true;phase=20;}return;}
            if(ticks%20==0)save(server,"progress",Map.of("phase",phase,"calls",calls(server),"ticks",ticks));
            if(phase>=1&&phase<=3){var b=actions.list(viewer.getUUID()).stream().filter(v->v.batchId().equals(batch)).findFirst().orElseThrow();if(!b.state().equals("COMPLETED")){require(!Set.of("FAILED","INTERRUPTED").contains(b.state()),"EVENT_CONFIGURATION_FAILED_"+b.error());return;}save(server,"phase-"+phase,b);UUID id=UUID.fromString(JSON.readTree(b.receipts().getLast().after().get("result")).path("id").asText());
                if(phase==1){recordSub=id;submit(server,"subscribe_events",subscription(viewer,"AGENT_WAKE","EVENT_WAKE_NATIVE_GOAL: 登录后提出一个普通说明问题，不执行世界操作。"));phase=2;return;}
                if(phase==2){wakeSub=id;submit(server,"subscribe_events",subscription(viewer,"AGENT_WAKE","EVENT_WAKE_CANCEL_SHOULD_NOT_REACH_PROVIDER"));phase=3;return;}
                cancelSub=id;Files.writeString(root(server).resolve("journal.json"),JSON.writeValueAsString(Map.of("agent",agent,"task",task,"record",recordSub,"wake",wakeSub,"cancel",cancelSub,"previous",previous)));phase=4;waitAt=ticks;return;
            }
            if(phase==4&&ticks-waitAt>=60){var c=runtime.context(task(server),UUID.randomUUID());require(calls(server)==0&&store.triggers(c,recordSub).isEmpty()&&store.triggers(c,wakeSub).isEmpty()&&store.triggers(c,cancelSub).isEmpty(),"EVENT_WAIT_POLLED_MODEL_OR_BACKFILLED");save(server,"result",Map.of("status","EVENT_SUBSCRIPTIONS_READY_WITHOUT_MODEL","calls",0,"waitTicks",60,"record",store.inspect(c,recordSub),"wake",store.inspect(c,wakeSub),"cancel",store.inspect(c,cancelSub),"systemInputInjected",false));finished=true;return;}
            if(phase==20){require(cancelHandled,"EVENT_EARLY_CANCEL_MISSING");var c=runtime.context(task(server),UUID.randomUUID());var values=store.triggers(c,wakeSub);if(values.isEmpty())return;require(values.size()==1,"EVENT_DUPLICATE_WAKE_TRIGGER");var trigger=values.getFirst();if(!trigger.state().equals("DISPATCHED")){require(!Set.of("CANCELLED","INTERRUPTED","MODEL_BUDGET_EXHAUSTED").contains(trigger.state()),"EVENT_WAKE_INTERRUPTED_"+trigger.error());return;}wakeTask=trigger.taskId();var child=MineAgentRuntimeServices.tasks(server).get(wakeTask).orElseThrow();
                if(child.steps().stream().noneMatch(s->s.status()==dev.mineagent.runtime.api.task.TaskNodeStatus.WAITING_FOR_PLAYER))return;
                require(calls(server)==1&&trigger.modelAttempts()==1&&trigger.event().source().equals("PLAYER_JOIN")&&trigger.event().author().equals(viewer.getUUID()),"EVENT_WAKE_CONTEXT_MISMATCH");var recorded=store.triggers(c,recordSub);require(recorded.size()==2&&recorded.stream().allMatch(t->t.state().equals("RECORDED"))&&recorded.stream().map(t->t.event().source()).collect(java.util.stream.Collectors.toSet()).equals(Set.of("PLAYER_JOIN","PLAYER_LEAVE")),"EVENT_NATIVE_SOURCE_HISTORY");store.ingest(trigger.event());require(store.triggers(c,wakeSub).size()==1&&calls(server)==1,"EVENT_DUPLICATE_CREATED_TASK_OR_MODEL");
                save(server,"wake",Map.of("trigger",trigger,"task",child,"recorded",recorded,"calls",calls(server),"question",MineAgentRuntimeServices.decisions(server).allFor(viewer.getUUID())));questionReady=true;phase=21;return;
            }
            if(phase==21&&questionCaptured){submit(server,"set_subscription_state",Map.of("subscription_id",wakeSub,"expected_revision",1,"state","CANCELLED"));phase=22;waitAt=ticks;return;}
            if(phase==22&&ticks-waitAt>=60){var b=actions.list(viewer.getUUID()).stream().filter(v->v.batchId().equals(batch)).findFirst().orElseThrow();require(b.state().equals("COMPLETED"),"EVENT_CANCEL_NOT_COMMITTED");var child=MineAgentRuntimeServices.tasks(server).get(wakeTask).orElseThrow();require(TaskAuthorityFence.revoked(child)&&calls(server)==1,"EVENT_CANCELLED_WAKE_RESUMED");var c=runtime.context(task(server),UUID.randomUUID());var stopped=store.triggers(c,wakeSub).getFirst();require(stopped.state().equals("CANCELLED")&&!runtime.workerPermit(child).getAsBoolean(),"EVENT_CANCEL_PERMIT_REMAINED");
                actions.finishTask(task(server),JSON.writeValueAsString(Map.of("checks",List.of(Map.of("kind","event_subscription","subscription_id",wakeSub,"revision",2,"state","CANCELLED"),Map.of("kind","event_subscription","subscription_id",cancelSub,"revision",2,"state","CANCELLED")))));save(server,"result",Map.of("status","NATIVE_EVENT_WAKE_AND_CANCELLATION_VERIFIED","child",child,"trigger",stopped,"calls",calls(server),"systemInputInjected",false,"realModel",false,"deliveryVerified",false,"fullV1",false));grant(server,viewer.getUUID(),previous);finished=true;
            }
        }catch(Exception e){failure=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();save(server,"failure",Map.of("phase",phase,"error",e.toString()));}
    }
}
