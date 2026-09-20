package dev.mineagent.runtime.neoforge.task;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.packages.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.api.task.*;
import dev.mineagent.runtime.core.task.*;
import dev.mineagent.runtime.core.shared.*;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.content.*;
import dev.mineagent.runtime.neoforge.ui.ServerPackageRuntime;
import dev.mineagent.runtime.worker.generation.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fixed General tool setup plus actual controlled Worker requests; all shared mutations go through production services. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class SharedAgentSmokeServer {
    private static final ObjectMapper JSON=new ObjectMapper();
    public static volatile boolean ready,questionReady,questionCaptured,finished;public static volatile String failure;
    private static UUID agent,instance,mainTask,recordSub,wakeSub,schedule,wakeTask,queuedTask,controlTask;private static SharedStateTarget target;private static int phase,started,after;private static boolean deniedOwner;
    public static boolean enabled(){return Boolean.getBoolean("mineagent.sharedAgentSmoke");}
    public static String stage(){return System.getProperty("mineagent.sharedAgentStage","prepare");}
    private static Path root(MinecraftServer s){return s.getServerDirectory().resolve("shared-agent-evidence");}
    private static void write(MinecraftServer s,String name,Object value)throws Exception{Files.createDirectories(root(s));Files.writeString(root(s).resolve(name),JSON.writeValueAsString(value));}
    private static void require(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    private static int calls(MinecraftServer s)throws Exception{return JSON.readTree(s.getServerDirectory().resolve("shared-agent-provider-count.json").toFile()).path("calls").asInt();}
    private static GeneratedFile file(String path,RuntimeResourceSide side,String media,String source){byte[] bytes=source.getBytes(StandardCharsets.UTF_8);return new GeneratedFile(path,side,media,dev.mineagent.runtime.core.objects.RuntimeModelBundle.hash(bytes),bytes);}
    public static final String SCRIPT="""
        var at=0;var fired=false;
        on('instance.create',function(){
          content.sharedDefine('state','schema',0,JSON.stringify({version:1,fields:{count:{type:'INTEGER',scope:'SHARED',minimum:0,maximum:1},entry:{type:'STRING'},ownerSecret:{type:'STRING',scope:'SHARED',read:'OWNER',write:'OWNER'}}}));
          content.sharedTransact('state','initialize',JSON.stringify({schema_version:1,conditions:[],writes:[{key:'count',op:'PUT',value:0},{key:'ownerSecret',op:'PUT',value:'OWNER_SECRET_NOT_FOR_AGENT'}]}));
          content.createObject('signal','models/signal.json',0,0,0);at=Number(server.getTickCount())+240;
        });
        on('instance.restore',function(){fired=true;content.createObject('signal','models/signal.json',0,0,0);content.sharedRead('state');});
        on('tick',function(t){if(!fired&&Number(t)>=at){fired=true;var receipt=JSON.parse(String(content.sharedTransact('state','authoritative-change',JSON.stringify({schema_version:1,conditions:[{key:'count',test:'EQ',value:0}],writes:[{key:'count',op:'PUT',value:1},{key:'ownerSecret',op:'PUT',value:'OWNER_SECRET_NOT_FOR_AGENT'}]}))));if(receipt.status!=='APPLIED')throw new Error('SHARED_FIXTURE_COMMIT_FAILED');}});
        """;
    private static ManagedTask task(MinecraftServer s,UUID id){return MineAgentRuntimeServices.tasks(s).get(id).orElseThrow();}
    private static Object call(String name,Object args)throws Exception{return Map.of("id",name+UUID.randomUUID(),"name",name,"arguments",JSON.writeValueAsString(args));}
    private static Map<String,Object> arguments(){return new LinkedHashMap<>(target.wire());}
    private static Map<String,Object> readArgs(String...keys){var a=arguments();a.put("keys",List.of(keys));return a;}
    private static Map<String,Object> transaction(String key,Object value){var a=arguments();a.put("transaction",Map.of("schema_version",1,"conditions",List.of(),"writes",List.of(Map.of("key",key,"op","PUT","value",value))));return a;}
    private static WorldActionJournal.Batch batch(MinecraftServer s,UUID id){return MineAgentRuntimeServices.taskExecutor(s).worldActions().list(task(s,id).ownerPlayerId()).stream().filter(b->b.taskId().equals(id)).reduce((a,b)->b).orElseThrow();}
    private static void grant(MinecraftServer s,UUID owner,Set<PermissionAction> values)throws Exception{var config=MineAgentRuntimeServices.config(s);require(config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),Map.of("runtime.initialized","true","permission.player."+owner,values.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(",")))),true).accepted(),"SHARED_AGENT_CONFIG");MineAgentRuntimeServices.permissions(s).setTrustedActions(owner,values);}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!enabled()||finished||failure!=null)return;var s=event.getServer();var player=s.getPlayerList().getPlayers().stream().filter(p->!(p instanceof dev.mineagent.runtime.neoforge.body.MineAgentPlayer)).findFirst().orElse(null);if(player==null)return;
        try{
            if(started==0)started=s.getTickCount();if(s.getTickCount()-started>2200)throw new IllegalStateException("SHARED_AGENT_TIMEOUT_"+phase);
            var actions=MineAgentRuntimeServices.taskExecutor(s).worldActions();var shared=MineAgentRuntimeServices.sharedStates(s);var events=MineAgentRuntimeServices.events(s);
            if(stage().equals("resume")){
                if(phase==0){var n=JSON.readTree(root(s).resolve("journal.json").toFile());agent=UUID.fromString(n.path("agent").asText());instance=UUID.fromString(n.path("instance").asText());mainTask=UUID.fromString(n.path("mainTask").asText());recordSub=UUID.fromString(n.path("recordSub").asText());wakeSub=UUID.fromString(n.path("wakeSub").asText());wakeTask=UUID.fromString(n.path("wakeTask").asText());schedule=UUID.fromString(n.path("schedule").asText());target=JSON.treeToValue(n.path("target"),SharedStateTarget.class);phase=1;after=s.getTickCount()+80;}
                if(s.getTickCount()<after||!WorldContentRuntime.get(s).active(instance)||MineAgentRuntimeServices.bodies(s).body(agent).isEmpty())return;
                require(calls(s)==2,"SHARED_RESTART_MODEL_REPLAY");require(task(s,wakeTask).status()==TaskStatus.PAUSED,"SHARED_RESTART_OLD_TASK_RUNNING");require(events.store().forTask(wakeTask).orElseThrow().state().equals("CANCELLED"),"SHARED_RESTART_TRIGGER_REPLAY");
                ready=true;write(s,"resume-result.json",Map.of("status","SHARED_AGENT_RESTART_NO_REPLAY_VERIFIED","calls",calls(s),"task",task(s,wakeTask),"trigger",events.store().forTask(wakeTask).orElseThrow(),"export",shared.diagnostics(),"instance",instance));finished=true;return;
            }
            if(phase==0){
                var grants=new HashSet<>(MineAgentRuntimeServices.permissions(s).trustedActions(player.getUUID()));grants.addAll(Set.of(PermissionAction.RUN_CODE,PermissionAction.MANAGE_PACKAGES,PermissionAction.DISCOVER_OBJECTS,PermissionAction.ACCESS_SHARED_STATE,PermissionAction.SUBSCRIBE_EVENTS,PermissionAction.SCHEDULE_TASKS));grant(s,player.getUUID(),grants);
                for(int x=515;x<=530;x++)for(int z=-2;z<=7;z++){s.overworld().setBlockAndUpdate(new net.minecraft.core.BlockPos(x,79,z),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());for(int y=80;y<=86;y++)s.overworld().setBlockAndUpdate(new net.minecraft.core.BlockPos(x,y,z),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());}
                player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);player.getAbilities().flying=true;player.onUpdateAbilities();player.teleportTo(s.overworld(),521,81,5,Set.of(),180,10,true);
                var js=file("server/main.js",RuntimeResourceSide.SERVER,"application/javascript",SCRIPT);var model=file("models/signal.json",RuntimeResourceSide.COMMON,"application/json",dev.mineagent.runtime.neoforge.ui.SharedMultiplayerFixture.MODEL);var entry=new RuntimeEntrypoint(js.path(),js.side(),js.sha256());var d=new RuntimeDefinition(UUID.randomUUID(),"Shared Agent signal fixture",RuntimeDefinitionKind.ENTITY,"server",Set.of(model.path()),Map.of(),1);
                var pack=ServerPackageRuntime.get(s).importOwned(player,UUID.randomUUID(),new ParsedRuntimePackage("Shared Agent fixture","1",RuntimePackageType.CONTENT,ActivationMode.HOT_RUNTIME,Map.of(),Set.of("RUN_CODE","state.shared"),Map.of("server",entry,"server.restore",entry),List.of(d),List.of(js,model)));
                var a=WorldContentRuntime.get(s).activate(player,UUID.randomUUID(),pack.packageId(),pack.revision(),d.definitionId(),new RuntimeInstanceLocation("minecraft:overworld",520.5,80,.5,0,0),true,true);require(a.state().equals("ACTIVE"),"SHARED_AGENT_ACTIVATION");instance=a.instanceId();var r=WorldContentRuntime.get(s).sharedDescriptor(instance);target=new SharedStateTarget(r.packageId(),instance,r.packageRevision(),r.canonicalSha256(),"state");
                agent=MineAgentRuntimeServices.bodies(s).createPersistentAt("Shared state Actor",player.getUUID(),s.overworld(),new net.minecraft.world.phys.Vec3(525.5,80,2.5)).agentId();phase=1;write(s,"fixture.json",Map.of("target",target,"agent",agent,"package",pack,"source","EXPLICIT_IMPORT","realModelCalls",0));
            }
            if(phase==1&&MineAgentRuntimeServices.bodies(s).body(agent).isPresent()){
                mainTask=MineAgentRuntimeServices.tasks(s).create(agent,player.getUUID(),"Shared Agent setup without model",1,List.of(new TaskStepSpec("execute",Set.of()))).taskId();
                try{shared.execute(task(s,mainTask),UUID.randomUUID(),"read_shared_state",JSON.writeValueAsString(readArgs("ownerSecret")));}catch(SecurityException expected){deniedOwner=true;}require(deniedOwner,"AGENT_BORROWED_OWNER_PARTITION");
                var list=new LinkedHashMap<>(target.wire());list.remove("namespace");actions.submit(task(s,mainTask),List.of(call("list_shared_namespaces",list),call("read_shared_state",readArgs("count","entry")),call("transact_shared_state",transaction("entry","AGENT_PRIVATE_ONLY")),call("read_shared_state",readArgs("entry","count"))));phase=2;
            }
            if(phase==2&&batch(s,mainTask).state().equals("COMPLETED")){
                write(s,"agent-tools.json",batch(s,mainTask));var a=arguments();a.putAll(Map.of("schema_version",1,"keys",List.of("count"),"when",List.of(Map.of("key","count","test","GTE","value",1)),"mode","RECORD_ONLY","cooldown_ms",0));var b=new LinkedHashMap<>(a);b.putAll(Map.of("mode","AGENT_WAKE","goal","SHARED_AGENT_WAKE_GOAL: 状态改变后询问下一步","max_wakes",1,"max_model_calls",2));actions.submit(task(s,mainTask),List.of(call("subscribe_shared_state",a),call("subscribe_shared_state",b)));phase=3;
            }
            if(phase==3&&batch(s,mainTask).state().equals("COMPLETED")){
                var receipts=batch(s,mainTask).receipts();recordSub=UUID.fromString(JSON.readTree(receipts.get(0).after().get("result")).path("id").asText());wakeSub=UUID.fromString(JSON.readTree(receipts.get(1).after().get("result")).path("id").asText());
                actions.submit(task(s,mainTask),List.of(call("create_schedule",Map.of("kind","EVENT_CONDITION","mode","RECORD_ONLY","subscription_id",recordSub,"subscription_revision",1,"fire_if_initially_matched",false))));phase=4;
            }
            if(phase==4&&batch(s,mainTask).state().equals("COMPLETED")){
                schedule=UUID.fromString(JSON.readTree(batch(s,mainTask).receipts().getFirst().after().get("result")).path("id").asText());require(calls(s)==0,"IDLE_SHARED_MODEL_REQUEST");ready=true;write(s,"subscriptions-ready.json",Map.of("record",events.store().subscriptionForRuntime(recordSub),"wake",events.store().subscriptionForRuntime(wakeSub),"schedule",MineAgentRuntimeServices.schedules(s).store().definitionForRuntime(schedule),"calls",0));phase=5;
            }
            if(phase==5){var candidates=events.store().taskBindings().stream().filter(t->t.subscriptionId().equals(wakeSub)).toList();if(candidates.isEmpty())return;require(candidates.size()==1,"SHARED_EXTRA_WAKE_TASK");wakeTask=candidates.getFirst().taskId();var questions=MineAgentRuntimeServices.decisions(s).pendingFor(player.getUUID()).stream().filter(d->d.title().equals("Native 共享状态唤醒")).toList();if(calls(s)==2&&!questions.isEmpty()){questionReady=true;if(questionCaptured){phase=6;after=s.getTickCount()+40;}}
            }
            if(phase==6&&s.getTickCount()>=after){
                var context=events.context(task(s,mainTask),UUID.randomUUID());var triggers=events.store().triggers(context,wakeSub);require(triggers.size()==2&&triggers.stream().anyMatch(t->t.state().equals("CYCLE_REJECTED")),"SHARED_SELF_TRIGGER_LOOP");
                var occurs=MineAgentRuntimeServices.schedules(s).store().occurrences(MineAgentRuntimeServices.schedules(s).context(task(s,mainTask),UUID.randomUUID()),schedule);require(occurs.size()==1&&occurs.getFirst().state().equals("RECORDED"),"SHARED_CONDITION_SCHEDULE");
                var goal=arguments();goal.putAll(Map.of("kind","shared_state","schema_version",1,"key","entry","expected_value","AGENT_PRIVATE_ONLY"));actions.finishTask(task(s,mainTask),JSON.writeValueAsString(Map.of("checks",List.of(goal))));require(task(s,mainTask).status()==TaskStatus.COMPLETED,"SHARED_GOAL_VERIFY");
                queuedTask=MineAgentRuntimeServices.tasks(s).create(agent,player.getUUID(),"Shared queued epoch rejection",1,List.of(new TaskStepSpec("execute",Set.of()))).taskId();actions.submit(task(s,queuedTask),List.of(call("transact_shared_state",transaction("entry","SHOULD_NOT_APPLY"))));var all=new HashSet<>(MineAgentRuntimeServices.permissions(s).trustedActions(player.getUUID()));var revoked=new HashSet<>(all);revoked.remove(PermissionAction.ACCESS_SHARED_STATE);grant(s,player.getUUID(),revoked);grant(s,player.getUUID(),all);controlTask=MineAgentRuntimeServices.tasks(s).create(agent,player.getUUID(),"Shared lifecycle control",1,List.of(new TaskStepSpec("execute",Set.of()))).taskId();
                events.execute(task(s,controlTask),UUID.randomUUID(),"set_subscription_state",JSON.writeValueAsString(Map.of("subscription_id",wakeSub,"expected_revision",1,"state","PAUSED")));events.execute(task(s,controlTask),UUID.randomUUID(),"set_subscription_state",JSON.writeValueAsString(Map.of("subscription_id",wakeSub,"expected_revision",2,"state","ACTIVE")));phase=7;after=s.getTickCount()+60;
            }
            if(phase==7&&s.getTickCount()>=after){require(calls(s)==2,"SHARED_REVOKED_REQUEST_REPLAY");require(Set.of("INTERRUPTED","FAILED").contains(batch(s,queuedTask).state()),"SHARED_QUEUED_EPOCH_NOT_REJECTED");var entry=shared.execute(task(s,controlTask),UUID.randomUUID(),"read_shared_state",JSON.writeValueAsString(readArgs("entry")));require(JSON.readTree(entry.get("result")).path("values").path("entry").asText().equals("AGENT_PRIVATE_ONLY"),"SHARED_QUEUED_WRITE_APPLIED");require(task(s,wakeTask).status()==TaskStatus.PAUSED,"SHARED_WAKE_NOT_PAUSED");
                write(s,"prepare-result.json",Map.of("status","NATIVE_SHARED_AGENT_TOOLS_WAKE_CYCLE_AND_FENCES_VERIFIED","calls",calls(s),"ownerFieldDenied",deniedOwner,"wakeTask",task(s,wakeTask),"trigger",events.store().forTask(wakeTask).orElseThrow(),"queued",batch(s,queuedTask),"export",shared.diagnostics()));write(s,"journal.json",Map.of("agent",agent,"instance",instance,"mainTask",mainTask,"recordSub",recordSub,"wakeSub",wakeSub,"wakeTask",wakeTask,"schedule",schedule,"target",target));finished=true;
            }
        }catch(Exception e){failure=e.toString();write(s,stage()+"-failure.json",Map.of("phase",phase,"error",failure));}
    }
}
