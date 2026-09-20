package dev.mineagent.runtime.neoforge.ui;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mineagent.runtime.api.decision.*;
import dev.mineagent.runtime.core.task.TaskStepSpec;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import dev.mineagent.runtime.neoforge.network.UiPayloads;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.nio.file.*;
import java.util.*;

/** Two different saved worlds served sequentially at the same address. No model calls or client static state. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class UiWorldSwitchSmokeServer {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String RUN=UUID.randomUUID().toString();
    private static UUID viewer,agent,firstDecision,secondDecision,firstTask,secondTask;
    private static int ticks,stopAt=-1,applied;private static boolean ready,finished;
    public static int stage(){return Integer.getInteger("mineagent.uiWorldSwitchServer",0);}
    @SubscribeEvent public static void tick(ServerTickEvent.Post e)throws Exception{
        if(stage()==0)return;var server=e.getServer();ticks++;
        try{
            if(!ready){ready=true;write(server,"ready.json",Map.of("run",RUN,"stage",stage(),"world",MineAgentRuntimeServices.worldId(server)));if(stage()==2)Files.writeString(Path.of(System.getProperty("mineagent.uiWorldSwitchSignal")),"READY");}
            var player=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)&&p.nameAndId().name().equals("UiMultiA")).findFirst().orElse(null);
            if(player!=null&&viewer==null){
                viewer=player.getUUID();var def=MineAgentRuntimeServices.bodies(server).create("World switch "+stage()+" "+RUN.substring(0,6),player,dev.mineagent.runtime.api.agent.AgentMode.CREATOR);agent=def.agentId();
                var grants=new HashSet<>(MineAgentRuntimeServices.permissions(server).trustedActions(viewer));grants.add(dev.mineagent.runtime.api.permission.PermissionAction.CHAT);MineAgentRuntimeServices.permissions(server).setTrustedActions(viewer,grants);
                var tasks=MineAgentRuntimeServices.tasks(server);var decisions=MineAgentRuntimeServices.decisions(server);
                var first=tasks.create(agent,viewer,"相同词语的第一个任务",1,List.of(new TaskStepSpec("apply",Set.of())));firstTask=first.taskId();firstDecision=UUID.randomUUID();
                decisions.openForTask(question(firstDecision,first.intentRevision(),"相同选项 · 第一张"),tasks,firstTask,Set.of("apply"));
                if(stage()==2){var second=tasks.create(agent,viewer,"相同词语的第二个任务",1,List.of(new TaskStepSpec("apply",Set.of())));secondTask=second.taskId();secondDecision=UUID.randomUUID();decisions.openForTask(question(secondDecision,second.intentRevision(),"相同选项 · 第二张"),tasks,secondTask,Set.of("apply"));}
                write(server,"fixture.json",Map.of("run",RUN,"stage",stage(),"viewer",viewer,"agent",agent,"firstDecision",firstDecision,"firstTask",firstTask,"secondDecision",secondDecision==null?"":secondDecision,"secondTask",secondTask==null?"":secondTask));
            }
            if(stage()==2&&secondDecision!=null&&MineAgentRuntimeServices.decisions(server).get(secondDecision).orElseThrow().status()==DecisionStatus.RESOLVED&&applied==0){
                var decisions=MineAgentRuntimeServices.decisions(server);var tasks=MineAgentRuntimeServices.tasks(server);var answer=decisions.acceptedAnswer(secondDecision).orElseThrow();
                if(!answer.selectedOptionIds().equals(List.of("b"))||answer.source()!=AnswerSource.CHAT||decisions.get(firstDecision).orElseThrow().status()!=DecisionStatus.OPEN||tasks.get(firstTask).orElseThrow().runnableStepIds().contains("apply"))throw new IllegalStateException("TASK_CONTEXT_CROSSED");
                var t=tasks.get(secondTask).orElseThrow();if(!t.runnableStepIds().contains("apply"))return;
                var result=tasks.completeStep(t.taskId(),t.revision(),"apply");if(!result.accepted())throw new IllegalStateException("TASK_RESUME_FAILED");applied++;
                write(server,"target-task-verified.json",Map.of("first",decisions.get(firstDecision).orElseThrow(),"second",decisions.get(secondDecision).orElseThrow(),"answer",answer,"task",result.task(),"applied",applied));
            }
            if(stopAt>=0&&ticks>=stopAt&&!finished){
                finished=true;var d=MineAgentRuntimeServices.decisions(server);write(server,"final.json",Map.of("stage",stage(),"first",d.get(firstDecision).orElseThrow(),"applied",applied,"providerCalls",0));server.halt(false);
            }
            if(ticks>12000)throw new IllegalStateException("WORLD_SWITCH_SERVER_TIMEOUT");
        }catch(Exception failure){write(server,"failure.json",Map.of("error",failure.toString()));server.halt(false);throw failure;}
    }
    private static DecisionRequest question(UUID id,long intent,String title){return new DecisionRequest(id,1,viewer,intent,DecisionKind.DESIGN,title,stage()==1?"WORLD_ONE_PRIVATE_ONLY":"WORLD_TWO_PRIVATE_ONLY",List.of(new DecisionOption("a","第一个","保留"),new DecisionOption("b","第二个","只应用当前任务")),SelectionMode.SINGLE,1,1,true,DecisionStatus.OPEN);}
    public static void handle(ServerPlayer player,UiPayloads.Command packet)throws Exception{
        if(stage()==0||!player.nameAndId().name().equals("UiMultiA"))throw new SecurityException("WORLD_FIXTURE_SOURCE");var server=player.level().getServer();String action=JSON.readTree(packet.json()).path("action").asText();
        if(action.equals("switch")){if(stage()!=1||MineAgentRuntimeServices.decisions(server).get(firstDecision).orElseThrow().status()!=DecisionStatus.OPEN)throw new IllegalStateException("PENDING_WORLD_ALREADY_CHANGED");stopAt=ticks+20;}
        if(action.equals("done")){if(stage()!=2||applied!=1)throw new IllegalStateException("WORLD_SWITCH_NOT_VERIFIED");stopAt=ticks+40;}
        Map<String,?> data=viewer==null?Map.of("ready",false,"run",RUN):Map.of("ready",true,"run",RUN,"stage",stage(),"agentId",agent,"firstDecision",firstDecision,"secondDecision",secondDecision==null?"":secondDecision,"applied",applied);
        PacketDistributor.sendToPlayer(player,new UiPayloads.Event(packet.requestId(),"uiWorldFixture",JSON.writeValueAsString(data)));
    }
    private static void write(net.minecraft.server.MinecraftServer server,String file,Object data)throws Exception{Path root=server.getServerDirectory().resolve("ui-world-switch-evidence").resolve(RUN);Files.createDirectories(root);Files.writeString(root.resolve(file),JSON.writeValueAsString(data));}
}
