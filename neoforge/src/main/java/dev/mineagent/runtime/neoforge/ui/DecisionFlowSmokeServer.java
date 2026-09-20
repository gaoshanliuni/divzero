package dev.mineagent.runtime.neoforge.ui;
import dev.mineagent.runtime.api.decision.*;
import dev.mineagent.runtime.api.task.*;
import dev.mineagent.runtime.core.task.TaskStepSpec;
import dev.mineagent.runtime.neoforge.*;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;
import java.nio.file.*;
/** Graphical decision-flow fixture. Native score increments are a bounded test step consumer, not generated-content proof. */
@EventBusSubscriber(modid="mineagent_runtime")
public final class DecisionFlowSmokeServer {
    public static final String RUN=UUID.randomUUID().toString();
    private static final List<String> CASES=List.of("multi","text","defer","escape","chat","race","cancel","auth","stale");
    public static volatile String currentCase,decisionId,agentId;public static volatile boolean next,caseVerified,finished,deferReady,staleReady,checkpointReady;
    private static final Map<String,Long> completedRevisions=new LinkedHashMap<>();
    public static boolean restoring(){return !System.getProperty("mineagent.decisionFlowResume","").isBlank();}
    private static int index,openedTick,deferredTick=-1,bodyTickBefore;private static UUID taskId,independentTaskId;private static String objective;private static boolean setup,parallelDone;
    public static String directory(){return "decision-flow-evidence/"+RUN;}
    @SubscribeEvent public static void tick(ServerTickEvent.Post e)throws Exception{
        if(!Boolean.getBoolean("mineagent.decisionFlowSmoke"))return;var server=e.getServer();var viewer=server.getPlayerList().getPlayers().stream().filter(p->!(p instanceof MineAgentPlayer)).findFirst().orElse(null);if(viewer==null)return;
        var tasks=MineAgentRuntimeServices.tasks(server);var decisions=MineAgentRuntimeServices.decisions(server);var json=new com.fasterxml.jackson.databind.ObjectMapper();var root=server.getServerDirectory().resolve(directory()).toAbsolutePath().normalize();Files.createDirectories(root);
        var port=new dev.mineagent.runtime.neoforge.scoreboard.NeoForgeScoreboardPort(server);
        if(restoring()){
            if(!setup){
                String previous=UUID.fromString(System.getProperty("mineagent.decisionFlowResume")).toString();var checkpoint=json.readTree(server.getServerDirectory().resolve("decision-flow-evidence").resolve(previous).resolve("restart-checkpoint.json").toFile());
                decisionId=checkpoint.path("decisionId").asText();taskId=UUID.fromString(checkpoint.path("taskId").asText());agentId=checkpoint.path("agentId").asText();objective=checkpoint.path("objective").asText();currentCase="restart";
                for(var entry:checkpoint.path("completedRevisions").properties())completedRevisions.put(entry.getKey(),entry.getValue().asLong());
                for(var old:decisions.allFor(viewer.getUUID()))if(old.status()==DecisionStatus.RESOLVED&&decisions.taskLink(old.decisionId()).map(l->completedRevisions.containsKey(l.taskId().toString())).orElse(false)){
                    var replay=decisions.submitForTask(viewer.getUUID(),tasks,decisions.acceptedAnswer(old.decisionId()).orElseThrow());if(!replay.accepted()||!replay.duplicate())throw new IllegalStateException("RESTART_REPLAY_MISMATCH");
                }
                decisions.resumeAcceptedTasks(tasks);for(var entry:completedRevisions.entrySet())if(tasks.get(UUID.fromString(entry.getKey())).orElseThrow().revision()!=entry.getValue())throw new IllegalStateException("RESTART_ADVANCED_OLD_TASK");
                if(decisions.get(UUID.fromString(decisionId)).orElseThrow().status()!=DecisionStatus.OPEN||decisions.acceptedAnswer(UUID.fromString(decisionId)).isPresent())throw new IllegalStateException("PENDING_RESTART_STATE");
                Files.writeString(root.resolve("restart-before.json"),json.writeValueAsString(Map.of("checkpoint",checkpoint,"scores",port.snapshot())));setup=true;
            }
            var q=decisions.get(UUID.fromString(decisionId)).orElseThrow();if(q.status()==DecisionStatus.RESOLVED&&!finished){
                var answer=decisions.acceptedAnswer(q.decisionId()).orElseThrow();if(!answer.selectedOptionIds().equals(List.of("c"))||!answer.customText().equals("重启草稿不自动提交"))throw new IllegalStateException("RESTART_DRAFT_LOST");
                decisions.resumeAcceptedTasks(tasks);var t=tasks.get(taskId).orElseThrow();if(!t.runnableStepIds().contains("choose"))return;
                port.addScore(objective,"restart",1);tasks.completeStep(taskId,t.revision(),"choose");Files.writeString(root.resolve("restart-verified.json"),json.writeValueAsString(Map.of("answer",answer,"task",tasks.get(taskId).orElseThrow(),"scores",port.snapshot())));finished=true;
            }return;
        }
        if(!setup){
            var agent=MineAgentRuntimeServices.bodies(server).definitions().stream().filter(a->a.ownerPlayerId().equals(viewer.getUUID())).findFirst().orElseGet(()->MineAgentRuntimeServices.bodies(server).create("Decision Flow Fixture",viewer,dev.mineagent.runtime.api.agent.AgentMode.CREATOR));agentId=agent.agentId().toString();
            var actions=new HashSet<>(MineAgentRuntimeServices.permissions(server).trustedActions(viewer.getUUID()));actions.add(dev.mineagent.runtime.api.permission.PermissionAction.CANCEL_AGENT_TASK);MineAgentRuntimeServices.permissions(server).setTrustedActions(viewer.getUUID(),actions);
            objective="df_"+RUN.substring(0,8);port.createObjective(objective,"dummy","选择流程效果","INTEGER",true,dev.mineagent.runtime.api.scoreboard.NumberFormatSpec.defaultFormat());setup=true;
        }
        if(next){index++;next=false;decisionId=null;taskId=null;caseVerified=false;deferReady=false;staleReady=false;parallelDone=false;deferredTick=-1;}
        if(index>=CASES.size()){
            currentCase="checkpoint";
            if(decisionId==null){var t=tasks.create(UUID.fromString(agentId),viewer.getUUID(),"选择重启 "+RUN,1,List.of(new TaskStepSpec("choose",Set.of())));taskId=t.taskId();var q=decisions.openForTask(new DecisionRequest(UUID.randomUUID(),1,viewer.getUUID(),t.revision(),DecisionKind.DESIGN,"重启保留草稿","草稿不应自动提交",List.of(new DecisionOption("a","A",""),new DecisionOption("b","B",""),new DecisionOption("c","C","")),SelectionMode.SINGLE,1,1,true,DecisionStatus.OPEN),tasks,taskId,Set.of("choose"));decisionId=q.decisionId().toString();}
            if(checkpointReady){if(decisions.acceptedAnswer(UUID.fromString(decisionId)).isPresent())throw new IllegalStateException("CHECKPOINT_AUTO_SUBMITTED");Files.writeString(root.resolve("restart-checkpoint.json"),json.writeValueAsString(Map.of("decisionId",decisionId,"taskId",taskId,"agentId",agentId,"objective",objective,"completedRevisions",completedRevisions)));finished=true;}return;
        }
        if(decisionId==null){currentCase=CASES.get(index);var task=tasks.create(UUID.fromString(agentId),viewer.getUUID(),"选择流程 "+RUN+" "+currentCase,1,List.of(new TaskStepSpec("choose",Set.of()),new TaskStepSpec("parallel",Set.of())));taskId=task.taskId();
            var q=decisions.openForTask(new DecisionRequest(UUID.randomUUID(),1,viewer.getUUID(),task.revision(),currentCase.equals("auth")?DecisionKind.AUTHORIZATION:DecisionKind.DESIGN,"流程 "+currentCase,"请处理本次 "+currentCase+" 问题；关闭不提交。",List.of(new DecisionOption("a","方案一","轻量结构"),new DecisionOption("b",currentCase.equals("auth")?"拒绝授权":"方案二","保留数据"),new DecisionOption("c","方案三","独立构造")),currentCase.equals("multi")?SelectionMode.MULTIPLE:SelectionMode.SINGLE,1,currentCase.equals("multi")?2:1,true,DecisionStatus.OPEN),tasks,taskId,Set.of("choose"));decisionId=q.decisionId().toString();openedTick=server.getTickCount();Files.writeString(root.resolve(currentCase+"-opened.json"),json.writeValueAsString(Map.of("decision",q,"task",tasks.get(taskId).orElseThrow())));
        }
        var q=decisions.get(UUID.fromString(decisionId)).orElseThrow();var task=tasks.get(taskId).orElseThrow();var body=MineAgentRuntimeServices.bodies(server).body(UUID.fromString(agentId)).orElseThrow();
        if(!parallelDone&&server.getTickCount()-openedTick>=20&&task.status()==TaskStatus.RUNNING&&task.runnableStepIds().contains("parallel")){
            port.setScore(objective,currentCase+"_parallel",1);tasks.completeStep(taskId,task.revision(),"parallel");parallelDone=true;
        }
        if(currentCase.equals("defer")&&q.status()==DecisionStatus.DEFERRED){if(deferredTick<0){deferredTick=server.getTickCount();bodyTickBefore=body.tickCount;
                independentTaskId=tasks.create(UUID.fromString(agentId),viewer.getUUID(),"独立等待回归 "+RUN,1,List.of(new TaskStepSpec("independent",Set.of()))).taskId();}
            var independent=tasks.get(independentTaskId).orElseThrow();
            if(server.getTickCount()-deferredTick>=20&&independent.runnableStepIds().contains("independent")){
                port.addScore(objective,"independent",1);tasks.completeStep(independentTaskId,independent.revision(),"independent");independent=tasks.get(independentTaskId).orElseThrow();completedRevisions.put(independentTaskId.toString(),independent.revision());}
            if(server.getTickCount()-deferredTick>=60&&body.tickCount>bodyTickBefore&&parallelDone&&independent.status()==TaskStatus.COMPLETED){deferReady=true;Files.writeString(root.resolve("defer-ticking.json"),json.writeValueAsString(Map.of("serverTicks",server.getTickCount()-deferredTick,"bodyTicks",body.tickCount-bodyTickBefore,"task",tasks.get(taskId).orElseThrow(),"independentTask",independent,"scores",port.snapshot())));}}
        if(currentCase.equals("stale")&&q.revision()==1&&server.getTickCount()-openedTick>=50){decisions.transition(q.decisionId(),viewer.getUUID(),q.revision(),DecisionStatus.DEFERRED,false);staleReady=true;}
        if(caseVerified)return;
        task=tasks.get(taskId).orElseThrow();
        if(currentCase.equals("cancel")){
            if(task.status()!=TaskStatus.CANCELLED||q.status()!=DecisionStatus.CANCELLED)return;
            var rejected=decisions.submitForTask(viewer.getUUID(),tasks,new DecisionAnswerSubmission(q.decisionId(),1,UUID.randomUUID(),List.of("a"),"过期输入",AnswerSource.UI));if(rejected.accepted())throw new IllegalStateException("CANCELLED_DECISION_ACCEPTED");
            Files.writeString(root.resolve(currentCase+"-verified.json"),json.writeValueAsString(Map.of("decision",q,"task",task,"rejected",rejected)));completedRevisions.put(taskId.toString(),task.revision());caseVerified=true;return;
        }
        if(q.status()!=DecisionStatus.RESOLVED||!parallelDone)return;
        var answer=decisions.acceptedAnswer(q.decisionId()).orElseThrow();List<String> expected=switch(currentCase){case "multi"->List.of("a","b");case "text"->List.of();case "race"->List.of("a");case "stale"->List.of("c");default->List.of("b");};
        if(!answer.selectedOptionIds().equals(expected))throw new IllegalStateException("DECISION_SELECTION_MISMATCH "+currentCase);
        if(currentCase.equals("chat")&&(answer.source()!=AnswerSource.CHAT||!answer.customText().equals("但不要透明")))throw new IllegalStateException("CHAT_DECISION_MISMATCH");
        if(currentCase.equals("defer")&&!answer.customText().equals("稍后保留草稿"))throw new IllegalStateException("DEFERRED_DRAFT_LOST");
        if(currentCase.equals("text")&&!answer.customText().equals("现场新方案：保留原结构，改成圆形入口"))throw new IllegalStateException("CUSTOM_ONLY_LOST");
        decisions.resumeAcceptedTasks(tasks);task=tasks.get(taskId).orElseThrow();if(!task.runnableStepIds().contains("choose"))return;
        port.addScore(objective,currentCase,1);tasks.completeStep(taskId,task.revision(),"choose");task=tasks.get(taskId).orElseThrow();
        if(task.status()!=TaskStatus.COMPLETED)throw new IllegalStateException("DECISION_TASK_NOT_COMPLETED");
        var replay=decisions.submitForTask(viewer.getUUID(),tasks,answer);if(!replay.accepted()||!replay.duplicate())throw new IllegalStateException("DECISION_REPLAY_FAILED");decisions.resumeAcceptedTasks(tasks);if(tasks.get(taskId).orElseThrow().revision()!=task.revision())throw new IllegalStateException("DECISION_REPLAY_ADVANCED_TASK");
        Files.writeString(root.resolve(currentCase+"-verified.json"),json.writeValueAsString(Map.of("decision",q,"answer",answer,"task",task,"replay",replay,"scores",port.snapshot())));completedRevisions.put(taskId.toString(),task.revision());caseVerified=true;
    }
}
