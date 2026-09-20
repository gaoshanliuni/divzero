package dev.mineagent.runtime.api.task;

public enum TaskNodeStatus {
    PENDING,
    RUNNING,
    WAITING_FOR_PLAYER,
    PAUSED,
    COMPLETED,
    CANCELLED,
    FAILED,
    SUPERSEDED
}
