package dev.mineagent.runtime.core.task;
import dev.mineagent.runtime.api.agent.AgentDefinition;
import dev.mineagent.runtime.api.task.ManagedTask;
import dev.mineagent.runtime.api.task.TaskStatus;
/** Current control authority is not conferred by an old task or cached response. */
public final class TaskAuthorityFence {
    public static final String REVOKED="AUTHORITY_REVOKED";
    private TaskAuthorityFence(){}
    public static boolean allowed(ManagedTask task,AgentDefinition definition,boolean operator){
        return task!=null&&definition!=null&&definition.agentId().equals(task.agentId())&&(operator||definition.ownerPlayerId().equals(task.ownerPlayerId())||definition.collaboratorPlayerIds().contains(task.ownerPlayerId()));
    }
    public static boolean revoked(ManagedTask task){return task!=null&&task.status()==TaskStatus.PAUSED&&REVOKED.equals(task.lastChangeReason());}
    public static boolean current(ManagedTask dispatched,ManagedTask live,AgentDefinition definition,boolean operator){return TaskResultFence.current(dispatched,live)&&allowed(live,definition,operator)&&!revoked(live);}
}
