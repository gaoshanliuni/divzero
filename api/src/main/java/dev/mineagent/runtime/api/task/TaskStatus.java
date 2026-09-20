package dev.mineagent.runtime.api.task;

public enum TaskStatus {
    RUNNING,
    WAITING_FOR_PLAYER,
    PAUSED,
    COMPLETED,
    CANCELLED,
    FAILED
}
