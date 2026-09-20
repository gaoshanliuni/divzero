package dev.mineagent.runtime.api.task;

import java.util.Objects;
import java.util.Set;

public record TaskStep(String stepId, Set<String> dependencies, TaskNodeStatus status) {
    public TaskStep {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(status, "status");
        dependencies = Set.copyOf(dependencies);
    }
}
