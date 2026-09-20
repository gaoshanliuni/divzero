package dev.mineagent.runtime.api.task;

import java.util.Objects;

public record TaskMutationResult(boolean accepted, String errorCode, ManagedTask task) {
    public TaskMutationResult {
        errorCode = errorCode == null ? "" : errorCode;
        Objects.requireNonNull(task, "task");
    }

    public static TaskMutationResult accepted(ManagedTask task) {
        return new TaskMutationResult(true, "", task);
    }

    public static TaskMutationResult rejected(ManagedTask task, String code) {
        return new TaskMutationResult(false, code, task);
    }
}
