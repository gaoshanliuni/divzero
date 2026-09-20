package dev.mineagent.runtime.neoforge.ui;

import com.fasterxml.jackson.databind.*;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.api.task.TaskStatus;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import java.nio.file.*;
import java.util.*;

/** Real Planner/Coder generation phase. It supplies a new requirement, never page or rule code. */
public final class FeedbackApplicationSmokeServer {
    private static final String RUN=UUID.randomUUID().toString();private static final ObjectMapper JSON=new ObjectMapper();
    public static final String GOAL="从零生成暮光航线训练营报名应用：仅RULE，不建方块实体。中文半透明UI，代号中英数下划线。仅一个namespace=state，两个键count/entry，名额1，entry为ACTOR私有；计数登记同笔条件事务。submit事件字段question，DETERMINISTIC/INDEPENDENT但不自动重放。create_world_package一次，inspect_content_contract列入口再读events/hash，ask_player等审查，不投递、不finish_task。";
    private static UUID agent,task;private static JsonNode publication;private static boolean ready,stopped;private static int ticks;private static final Set<UUID> done=new HashSet<>();
    public static boolean enabled(){return System.getProperty("mineagent.feedbackApplication","").equals("signup-generation");}
    private static Path root(MinecraftServer s){return s.getServerDirectory().resolve("feedback-application-evidence");}
    private static void write(MinecraftServer s,String name,Object data)throws Exception{Files.createDirectories(root(s));Files.writeString(root(s).resolve(name),JSON.writeValueAsString(data));}
    public static void tick(MinecraftServer s)throws Exception{
        if(!enabled()||stopped)return;ticks++;
        try{
            var owner=s.getPlayerList().getPlayers().stream().filter(p->p.nameAndId().name().equals("DeliveryA")).findFirst().orElse(null);if(owner==null)return;
            if(agent==null){
                if(GOAL.codePointCount(0,GOAL.length())>256)throw new IllegalStateException("APPLICATION_GOAL_BUDGET");var config=MineAgentRuntimeServices.config(s);if(!config.snapshot().values().getOrDefault("provider.openai.model","").equals("deepseek-flash")||config.snapshot().values().getOrDefault("provider.openai.apiKey","").isBlank())throw new IllegalStateException("APPLICATION_REAL_PROVIDER_REQUIRED");
                var grants=new HashSet<>(MineAgentRuntimeServices.permissions(s).trustedActions(owner.getUUID()));grants.addAll(Set.of(PermissionAction.MANAGE_PACKAGES,PermissionAction.RUN_CODE,PermissionAction.DISCOVER_OBJECTS,PermissionAction.ACCESS_SHARED_STATE,PermissionAction.OFFER_CONTENT,PermissionAction.RECEIVE_UI_FEEDBACK,PermissionAction.SUBSCRIBE_EVENTS,PermissionAction.SCHEDULE_TASKS));
                var patch=Map.of("runtime.initialized","true","voice.output.enabled","false","permission.player."+owner.getUUID(),grants.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(",")));if(!config.apply(new dev.mineagent.runtime.api.config.ConfigPatch(config.snapshot().revision(),patch),true).accepted())throw new IllegalStateException("APPLICATION_PROFILE_CONFIG");MineAgentRuntimeServices.permissions(s).setTrustedActions(owner.getUUID(),grants);
                agent=MineAgentRuntimeServices.bodies(s).createPersistentAt("Application designer",owner.getUUID(),s.overworld(),owner.position().add(2,0,0)).agentId();task=dev.mineagent.runtime.neoforge.task.ServerTaskStart.start(owner,UUID.randomUUID(),agent,GOAL,1,false).taskId();write(s,"fixture.json",Map.of("run",RUN,"goal",GOAL,"task",task,"agent",agent,"owner",owner.getUUID(),"model","deepseek-flash","source","REAL_PLANNER_AND_CODER_NEW_APPLICATION","systemInputInjected",false,"fullV1",false));
            }
            var current=MineAgentRuntimeServices.tasks(s).get(task).orElseThrow();var actions=MineAgentRuntimeServices.taskExecutor(s).worldActions();var batches=actions.list(owner.getUUID()).stream().filter(b->b.taskId().equals(task)).toList();var packages=ServerPackageRuntime.get(s);var jobs=packages.list(owner.getUUID()).stream().filter(j->j.agentId().equals(agent)).toList();
            if(s.getTickCount()%20==0){write(s,"task.json",current);write(s,"rounds.json",batches);write(s,"generations.json",jobs);}
            if(actions.planningAttempts(current)>8||jobs.size()>1||batches.stream().flatMap(b->b.actions().stream()).anyMatch(a->!Set.of("create_world_package","inspect_content_contract","inspect_world_content").contains(a.tool())))throw new IllegalStateException("APPLICATION_GENERATION_PHASE_SCOPE");
            for(var job:jobs){
                if(Set.of("FAILED","INTERRUPTED","CANCELLED","STALE").contains(job.state())){write(s,"generation-failed.json",job);if(!job.rawOutputSha256().isBlank())Files.write(root(s).resolve("failed-model-output.json"),packages.worldContent().read(job.rawOutputSha256()));throw new IllegalStateException("APPLICATION_GENERATION_"+job.state()+"_"+job.errorCode());}
                if(job.state().equals("PUBLISHED")&&publication==null){var pkg=packages.worldLibrary().get(job.packageId()).orElseThrow();if(pkg.origin()!=dev.mineagent.runtime.api.packages.PackageOrigin.GENERATED)throw new IllegalStateException("APPLICATION_ORIGIN");publication=JSON.valueToTree(pkg);write(s,"package.json",pkg);write(s,"generation.json",job);Files.write(root(s).resolve("model-output.json"),packages.worldContent().read(job.rawOutputSha256()));Path target=root(s).resolve("package").toAbsolutePath().normalize();for(var ref:pkg.resources().values()){var p=target.resolve(ref.path()).normalize();if(!p.startsWith(target))throw new IllegalStateException("APPLICATION_EXPORT_PATH");Files.createDirectories(p.getParent());Files.write(p,packages.worldContent().read(ref.sha256()));}}
            }
            var pending=MineAgentRuntimeServices.decisions(s).pendingFor(owner.getUUID());boolean inspected=batches.stream().flatMap(b->b.receipts().stream()).filter(r->r.tool().equals("inspect_content_contract")&&r.verified()).anyMatch(r->{try{return JSON.readTree(r.after().get("result")).path("feedbackDeclared").asBoolean();}catch(Exception ignored){return false;}});
            ready=publication!=null&&inspected&&!pending.isEmpty();
            if(ready)write(s,"review-ready.json",Map.of("task",current,"planningCalls",actions.planningAttempts(current),"questions",pending,"package",publication,"nativeActivated",false,"requiresOperatorSourceReview",true));
            if(done.size()==2&&ready){MineAgentRuntimeServices.tasks(s).transition(task,current.revision(),true,TaskStatus.CANCELLED);write(s,"result.json",Map.of("status","REAL_GENERATION_AND_MODEL_CONTRACT_INSPECTION_REVIEW_REQUIRED","package",publication,"planningCalls",actions.planningAttempts(current),"coderCalls",jobs.size(),"nativeActivated",false,"taskStoppedForOperatorReview",true,"fullV1",false));stopped=true;MineAgentRuntimeMod.LOGGER.info("MINEAGENT_DELIVERY_SERVER_OK realApplicationGeneration={}",RUN);s.halt(false);return;}
            if(!ready&&Set.of(TaskStatus.PAUSED,TaskStatus.CANCELLED,TaskStatus.FAILED,TaskStatus.COMPLETED).contains(current.status()))throw new IllegalStateException("APPLICATION_PLANNER_STOPPED_"+current.status());
            if(ticks>12000)throw new IllegalStateException("APPLICATION_GENERATION_TIMEOUT");
        }catch(Exception failure){stopped=true;write(s,"failure.json",Map.of("error",failure.toString()));if(task!=null){var current=MineAgentRuntimeServices.tasks(s).get(task).orElse(null);if(current!=null&&current.status()==TaskStatus.RUNNING)MineAgentRuntimeServices.tasks(s).transition(task,current.revision(),true,TaskStatus.CANCELLED);}s.halt(false);throw failure;}
    }
    public static void handle(ServerPlayer player,UiPayloads.Command packet)throws Exception{
        var args=JSON.readTree(packet.json());String action=args.path("action").asText();if(!args.isObject()||args.size()!=1||!Set.of("info","done").contains(action))throw new IllegalArgumentException("APPLICATION_FIXTURE_ACTION");if(action.equals("done"))done.add(player.getUUID());
        var data=new LinkedHashMap<String,Object>();data.put("run",RUN);data.put("ready",agent!=null);data.put("reviewReady",ready);data.put("owner",player.nameAndId().name().equals("DeliveryA"));data.put("published",publication!=null);
        PacketDistributor.sendToPlayer(player,new UiPayloads.Event(packet.requestId(),"deliveryFixture",JSON.writeValueAsString(data)));
    }
}
