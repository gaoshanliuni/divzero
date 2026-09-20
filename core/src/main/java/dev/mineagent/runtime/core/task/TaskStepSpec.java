package dev.mineagent.runtime.core.task;

import java.util.Set;

public record TaskStepSpec(String stepId, Set<String> dependencies) {
    public TaskStepSpec {
        dependencies = Set.copyOf(dependencies);
    }
}
