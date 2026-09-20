package dev.mineagent.runtime.core.task;

import dev.mineagent.runtime.api.task.ManagedTask;
import dev.mineagent.runtime.api.task.TaskStatus;

public final class TaskResultFence {
    private TaskResultFence() {}
    public static boolean current(ManagedTask dispatched, ManagedTask live) {
        return dispatched != null && live != null && live.status() == TaskStatus.RUNNING
                && dispatched.taskId().equals(live.taskId()) && dispatched.worldId().equals(live.worldId())
                && dispatched.agentId().equals(live.agentId()) && dispatched.ownerPlayerId().equals(live.ownerPlayerId())
                && dispatched.revision() == live.revision();
    }
}
