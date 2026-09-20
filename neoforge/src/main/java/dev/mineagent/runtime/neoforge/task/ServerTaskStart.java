package dev.mineagent.runtime.neoforge.task;
import dev.mineagent.runtime.api.task.ManagedTask;
import dev.mineagent.runtime.api.permission.PermissionAction;
import dev.mineagent.runtime.core.task.TaskStepSpec;
import dev.mineagent.runtime.neoforge.MineAgentRuntimeServices;
import dev.mineagent.runtime.neoforge.body.MineAgentPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import java.util.*;

/** Shared trusted-web/native entry. Starting another Agent requires persistent ownership/collaboration authority. */
public final class ServerTaskStart {
    private ServerTaskStart(){}
    public static boolean allowed(ServerPlayer viewer,UUID agent){
        var server=viewer.level().getServer();if(!server.isSameThread()||viewer instanceof MineAgentPlayer)return false;
        var permissions=MineAgentRuntimeServices.permissions(server);boolean op=viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        var definition=MineAgentRuntimeServices.bodies(server).definitions().stream().filter(a->a.agentId().equals(agent)).findFirst().orElse(null);
        return definition!=null&&permissions.allowed(viewer.getUUID(),op,PermissionAction.START_TASK)&&permissions.canMutateAgent(definition,viewer.getUUID(),op);
    }
    public static boolean revokedReplay(ServerPlayer viewer,UUID operation){var tasks=MineAgentRuntimeServices.tasks(viewer.level().getServer());return tasks.get(tasks.startTaskId(viewer.getUUID(),operation)).filter(dev.mineagent.runtime.core.task.TaskAuthorityFence::revoked).isPresent();}
    public static ManagedTask start(ServerPlayer viewer,UUID operation,UUID agent,String prompt,int priority,boolean uiOnly)throws Exception{
        var server=viewer.level().getServer();if(!allowed(viewer,agent))throw new SecurityException("TASK_START_DENIED");
        if(revokedReplay(viewer,operation))throw new IllegalStateException("TASK_REPLAN_REQUIRED");
        String step=uiOnly?"plan_ui":"plan";return MineAgentRuntimeServices.tasks(server).createIdempotent(operation,agent,viewer.getUUID(),prompt,priority,List.of(new TaskStepSpec(step,Set.of()),new TaskStepSpec("execute",Set.of(step))));
    }
    public static boolean canReplan(ServerPlayer viewer,ManagedTask task){
        var server=viewer.level().getServer();return task.ownerPlayerId().equals(viewer.getUUID())&&allowed(viewer,task.agentId())
                &&(task.status()==dev.mineagent.runtime.api.task.TaskStatus.PAUSED||task.status()==dev.mineagent.runtime.api.task.TaskStatus.FAILED)
                &&task.steps().stream().anyMatch(s->Set.of("plan","replan").contains(s.stepId()))&&!MineAgentRuntimeServices.events(server).feedbackTask(task.taskId())
                &&MineAgentRuntimeServices.agentUiLinks(server).forTask(task.taskId(),task.intentRevision()).isEmpty();
    }
    public static String replanParent(ServerPlayer viewer,ManagedTask task){
        try{return MineAgentRuntimeServices.tasks(viewer.level().getServer()).replanSource(task.taskId()).filter(s->s.original().ownerPlayerId().equals(viewer.getUUID())).map(s->s.original().taskId().toString()).orElse("");}
        catch(Exception failure){throw new IllegalStateException("TASK_REPLAN_METADATA_UNAVAILABLE",failure);}
    }
    public static ManagedTask replan(ServerPlayer viewer,UUID operation,UUID sourceId,long sourceRevision,String goal,String note,boolean confirmed)throws Exception{
        if(!confirmed)throw new IllegalArgumentException("TASK_REPLAN_CONFIRM_REQUIRED");var server=viewer.level().getServer();var tasks=MineAgentRuntimeServices.tasks(server);var source=tasks.get(sourceId).orElseThrow();
        if(!source.ownerPlayerId().equals(viewer.getUUID())||!allowed(viewer,source.agentId()))throw new SecurityException("TASK_REPLAN_DENIED");
        if(revokedReplay(viewer,operation))throw new IllegalStateException("TASK_REPLAN_REQUIRED");
        var prior=tasks.get(tasks.startTaskId(viewer.getUUID(),operation));
        if(prior.isEmpty()){
            if(!canReplan(viewer,source))throw new IllegalStateException("TASK_REPLAN_NOT_AVAILABLE");
            if(!MineAgentRuntimeServices.bodies(server).body(source.agentId()).map(MineAgentPlayer::canAct).orElse(false))throw new IllegalStateException("BODY_UNAVAILABLE");
        }
        return tasks.createReplanned(operation,viewer.getUUID(),sourceId,sourceRevision,goal,note);
    }
}
